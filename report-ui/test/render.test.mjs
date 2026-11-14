// The report page, rendered headless in Chrome from the built report.html with a report in its
// slot: each view shows what the report holds, and nothing in a report can run as script, hide
// text from what Copy puts on the clipboard, or slow a large report down.
//
//   node --test report-ui/test/
import assert from 'node:assert/strict';
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import { after, before, describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { PAGE, SLOT, build, embed } from '../build.mjs';
import { launch } from './chrome.mjs';

const HERE = fileURLToPath(new URL('.', import.meta.url));
const fixture = (name) => readFileSync(join(HERE, 'fixtures', name), 'utf8');
const JAVA = fixture('java-pricing.json');
const RUST = fixture('rust-pricing.json');

let chrome, dir;
before(async () => { chrome = await launch(); dir = mkdtempSync(join(tmpdir(), 'report-ui-pages-')); });
after(async () => { await chrome?.close(); rmSync(dir, { recursive: true, force: true }); });

// A page with `json` in it, opened at `route`. The clipboard is the page's own, read back by copied().
async function open(json, route = '#/', { prompt = false, ...options } = {}) {
  const file = join(dir, `${Math.random().toString(36).slice(2)}.html`);
  writeFileSync(file, json == null ? readFileSync(PAGE, 'utf8') : embed(build({ prompt }), json));
  const page = await chrome.page(options);
  await page.goto(`file://${file}${route}`);
  await page.eval(`window.__copied = null; Object.defineProperty(navigator, 'clipboard', { value: { writeText: (t) => { window.__copied = t; return Promise.resolve(); } } })`);
  page.copied = async (selector) => { await page.click(selector); await page.eval('new Promise(r => setTimeout(r, 50))'); return page.eval('window.__copied'); };
  return page;
}

function report(files, extra = {}) {
  return JSON.stringify({ schemaVersion: '2', thresholds: { high: 80, low: 60 }, files, ...extra });
}

describe('the built page', () => {
  test('is the build of src/ and fonts/, with one empty slot', () => {
    const page = readFileSync(PAGE, 'utf8');
    assert.equal(page, build(), 'report.html is out of date: run node report-ui/build.mjs');
    assert.equal(page.split(SLOT).length, 2);
    assert.doesNotMatch(page, /https?:\/\/(?!docs\.mutastic\.io|www\.w3\.org)[^\s'"]*\.(js|css|woff2?)/, 'the page loads nothing from the network');
    assert.match(page, /var PROMPT = false;/, 'Copy as prompt stays off until its framing is in');
  });

  test('with nothing in its slot, asks for a report', async () => {
    const page = await open(null);
    assert.match(await page.text('main'), /No report in this page/);
    assert.deepEqual(page.errors, []);
    await page.close();
  });
});

describe('the Java pricing sample', () => {
  test('lists its files with the run score and verdict', async () => {
    const page = await open(JAVA);
    const text = await page.text('main');
    assert.match(text, /90\.9%/);
    assert.match(text, /The run passed its 80% threshold\./);
    assert.match(text, /Pricing\.java/);
    assert.match(await page.text('#tabs'), /Files\s+1\s+Tests to write\s+1\s+Mutants\s+11\s+Tests\s+3/);
    assert.match(await page.text('#ver'), /java edition · 2026-11-13 20:34 UTC/);
    assert.match(await page.text('#foot'), /with jdk 17/);
    assert.deepEqual(page.errors, []);
    await page.close();
  });

  test('shows a file with its source coloured and its miss under the line', async () => {
    const page = await open(JAVA, '#/file/src/main/java/com/example/pricing/Pricing.java');
    assert.equal(await page.count('.ln'), 31);
    assert.ok(await page.count('.ln .tk-kw') > 5, 'keywords are coloured');
    assert.ok(await page.count('.ln .tk-com') >= 3, 'comments are coloured');
    assert.equal(await page.count('.inl .mrow.Survived'), 1);
    await page.click('.mk button[data-line="17"]');
    assert.equal(await page.count('.inl .mrow'), 3, 'a line opened shows every mutant on it');
    await page.close();
  });

  test('drills into a miss: the change, why, the test to add, the inputs and the commands', async () => {
    const page = await open(JAVA, '#/m/m855c8e3c');
    const text = await page.text('main');
    assert.match(text, /No test checks discount\(\) at total == 100/);
    assert.equal(await page.text('.frame mark'), '>=');
    assert.match(await page.text('.diff'), /- if \(total >= 100\) \{\n\+ if \(total > 100\) \{/);
    assert.match(text, /Every test calls discount\(\) away from the boundary\./);
    assert.match(text, /in PricingTest/);
    assert.match(text, /total\s*50\s*250\s*none is\s*100/);
    assert.match(text, /mutastic verify m855c8e3c java-pricing/);
    assert.match(text, /mutastic ignore m855c8e3c --reason="<why>" java-pricing/);
    assert.match(text, /in Pricing\.discount\(\)/);
    assert.equal(await page.eval(`document.querySelector('a[target=_blank]').href`), 'https://docs.mutastic.io/concepts/operators/#boundary');
    assert.equal(await page.count('[data-prompt]'), 0, 'no Copy as prompt in the default build');
    await page.close();
  });

  test('Copy puts the miss on the clipboard as the page says it', async () => {
    const page = await open(JAVA, '#/m/m855c8e3c');
    assert.equal(await page.copied('.dd-head [data-list]'), fixture('java-pricing-copy.md'));
    assert.equal(await page.text('.dd-head [data-list]'), 'Copied');
    await page.close();
  });

  test('Copy as prompt, when built in, sits beside Copy and gives the agent its rules', async () => {
    const page = await open(JAVA, '#/missed', { prompt: true });
    assert.equal(await page.count('.pair [data-list] + [data-prompt]'), 1);
    const text = await page.copied('.hero [data-prompt]');
    assert.match(text, /^Write the test this mutant asks for\./);
    assert.match(text, /done when: mutastic verify m855c8e3c java-pricing passes/);
    await page.close();
  });

  test('filters every mutant by status and operator', async () => {
    const page = await open(JAVA, '#/mutants');
    assert.equal(await page.count('#app .mrow'), 1, 'misses only, at first');
    await page.click('.chip[data-st="all"]');
    assert.equal(await page.count('#app .mrow'), 11);
    await page.eval(`(() => { const s = document.getElementById('mutator'); s.value = 'return-value'; s.dispatchEvent(new Event('change', { bubbles: true })); })()`);
    assert.equal(await page.count('#app .mrow'), 4);
    assert.match(await page.text('main'), /4 of 11 mutants shown/);
    await page.eval('document.activeElement.blur()');
    await page.key('j');
    assert.match(await page.eval('location.hash'), /^#\/m\/m/, 'j goes to the first mutant the filters show');
    assert.match(await page.text('.dd-head'), /return-value/);
    await page.close();
  });

  test('shows a test with its source and what it let through', async () => {
    const page = await open(JAVA, '#/tests');
    await page.eval(`document.querySelector('details.trow[data-t$="bigOrdersGetTenPercentOff"]').open = true`);
    await page.eval('new Promise(r => setTimeout(r, 50))');
    const body = await page.text('details[open] .body');
    assert.match(body, /void bigOrdersGetTenPercentOff\(\)/);
    assert.match(body, /Ran these and still passed/);
    assert.ok(await page.count('details[open] .frame .tk-an') >= 1, '@Test is coloured');
    await page.close();
  });

  test('names every control and link to a screen reader', async () => {
    for (const route of ['#/', '#/file/src/main/java/com/example/pricing/Pricing.java', '#/m/m855c8e3c', '#/mutants', '#/tests']) {
      const page = await open(JAVA, route);
      const unnamed = (await page.axTree()).filter((n) => !n.ignored && ['button', 'link', 'combobox', 'searchbox', 'textbox'].includes(n.role?.value) && !n.name?.value?.trim());
      assert.deepEqual(unnamed.map((n) => n.role.value), [], `${route}: a control with no name`);
      await page.close();
    }
  });

  test('renders in dark mode and Windows high contrast without a fault', async () => {
    for (const options of [{ dark: true }, { forcedColors: true }, { width: 375 }]) {
      const page = await open(JAVA, '#/m/m855c8e3c', options);
      assert.deepEqual(page.errors, []);
      assert.equal(await page.eval('document.documentElement.scrollWidth <= innerWidth'), true, 'nothing wider than the screen');
      await page.close();
    }
  });
});

describe('the Rust pricing sample', () => {
  test('drills into its miss in Rust terms', async () => {
    const page = await open(RUST, '#/m/mb2f8ae57');
    const text = await page.text('main');
    assert.match(text, /mutastic verify mb2f8ae57 \./);
    assert.match(text, /in tests/);
    assert.match(await page.text('pre.code'), /#\[test\]\nfn discount_at_the_total_edge\(\)/);
    assert.ok(await page.count('.frame .tk-kw') >= 1);
    assert.equal(await page.eval(`document.querySelector('a[target=_blank]').href`), 'https://docs.mutastic.io/rust/operators/conditions/#boundary');
    assert.match(await page.text('#ver'), /rust edition/);
    assert.deepEqual(page.errors, []);
    await page.close();
  });
});

describe('a report written to break the page', () => {
  const evil = '<img src=x onerror="window.__owned=1">';
  const source = `fn main() {\n    let s = "</script><script>window.__owned=1</script>";\n    let t = "${evil}";\n    if a\n        && b\n        && c {}\n}\n`;
  const json = report({
    [`src/${evil}.rs`]: {
      language: 'rust', source,
      mutants: [
        { id: 'm0000000a', mutatorName: evil, replacement: evil, description: `\`${evil}\``, status: 'Survived', statusReason: `no test sees ${evil}`,
          location: { start: { line: 2, column: 13 }, end: { line: 2, column: 20 } }, coveredBy: [`tests::${evil}`],
          mutastic: { fix: `check ${evil}`, evidence: ['shown \u202Eevil\u202C here\u200B and \u001b[31mred'], inputs: { [evil]: [evil] },
            suggestedTest: { testClass: evil, code: 'let x = "```";\nlet y = "````";', call: evil, today: evil, withMutant: evil } } },
        { id: 'm0000000b', mutatorName: 'logical', replacement: 'a || b || c', description: '`&&` becomes `||`', status: 'Survived',
          location: { start: { line: 4, column: 8 }, end: { line: 6, column: 13 } }, coveredBy: [] },
        { id: '__proto__', mutatorName: 'x', status: 'Killed', location: { start: { line: 1, column: 1 }, end: { line: 1, column: 2 } } },
      ],
    },
  }, { projectRoot: `javascript:alert(1)//${evil}`, mutastic: { runDir: "my dir; rm -rf ~", docsBase: 'javascript:alert(1)/', operatorDocs: { [evil]: 'x' } }, testFiles: { [`../${evil}`]: { tests: [{ id: `tests::${evil}`, name: evil }] } } });

  test('shows it all as text and runs none of it', async () => {
    for (const route of ['#/', `#/file/src/${encodeURIComponent(evil)}.rs`, '#/m/m0000000a', '#/m/m0000000b', '#/missed', '#/mutants', '#/tests']) {
      const page = await open(json, route);
      await page.eval(`document.querySelectorAll('details').forEach(d => d.open = true)`);
      await page.eval('new Promise(r => setTimeout(r, 100))');
      assert.equal(await page.eval('window.__owned'), undefined, `${route} ran the report's script`);
      assert.equal(await page.count('#app img, #app script'), 0, `${route} made an element from the report's text`);
      assert.deepEqual(page.errors, [], route);
      await page.close();
    }
  });

  test('quotes a run directory and links nowhere unsafe', async () => {
    const page = await open(json, '#/m/m0000000a');
    assert.match(await page.text('main'), /mutastic verify m0000000a 'my dir; rm -rf ~'/);
    assert.equal(await page.count('a[href^="javascript"]'), 0);
    assert.equal(await page.count('a[target=_blank]'), 0, 'no docs link from a base that isn\'t https');
    await page.close();
  });

  test('marks a change over several lines on each of them', async () => {
    const page = await open(json, '#/m/m0000000b');
    assert.equal(await page.count('.frame mark'), 3);
    assert.equal(await page.eval(`[...document.querySelectorAll('.frame mark')].map(m => m.textContent).join('|')`), 'a|&& b|&& c');
    await page.close();
    const file = await open(json, `#/file/src/${encodeURIComponent(evil)}.rs`);
    assert.equal(await file.count('.ln.s'), 4, 'the gutter shows the lines each miss spans');
    await file.close();
  });

  test('Copy shows hidden characters as escapes and fences code longer than any backticks in it', async () => {
    const page = await open(json, '#/m/m0000000a');
    const text = await page.copied('.dd-head [data-list]');
    assert.match(text, /Shown \\u\{202E\}evil\\u\{202C\} here\\u\{200B\} and \\u\{001B\}\[31mred/);
    assert.doesNotMatch(text, /[\u202A-\u202E\u200B\u001b]/);
    assert.match(text, /## No test sees \\<img src=x/, 'HTML outside code is escaped, so a paste can\'t hide text in a comment');
    assert.match(text, /`src\/<img src=x onerror="window.__owned=1">.rs:2`/, 'in code it stays as it is');
    assert.match(text, /\n`````rust\nlet x = "```";\nlet y = "````";\n`````\n/);
    await page.close();
  });
});

describe('a large report', () => {
  // 40 folders of 25 files, 3 mutants each, and one file of 30,000 lines.
  const files = {};
  const statuses = ['Killed', 'Survived', 'NoCoverage', 'Killed', 'Ignored'];
  let n = 0;
  for (let d = 0; d < 40; d++) {
    for (let f = 0; f < 25; f++) {
      files[`src/main/java/com/example/p${d}/C${f}.java`] = { language: 'java', source: 'class C {\n  int f(int a) {\n    return a + 1;\n  }\n}\n',
        mutants: [0, 1, 2].map((k) => ({ id: `m${(n++).toString(16).padStart(8, '0')}`, mutatorName: k ? 'math' : 'boundary', description: '`+` becomes `-`', status: statuses[(d + f + k) % 5],
          location: { start: { line: 3, column: 14 }, end: { line: 3, column: 15 } }, coveredBy: [`com.example.T${d}#t${f}`] })) };
    }
  }
  const big = Array.from({ length: 30000 }, (_, i) => `    int v${i} = ${i} + 1; // line ${i + 1}`).join('\n');
  files['src/main/java/com/example/Big.java'] = { language: 'java', source: `class Big {\n${big}\n}\n`,
    mutants: Array.from({ length: 2000 }, (_, i) => ({ id: `m${(n++).toString(16).padStart(8, '0')}`, mutatorName: 'math', description: '`+` becomes `-`', status: statuses[i % 5],
      location: { start: { line: 2 + i * 15, column: 20 }, end: { line: 2 + i * 15, column: 21 } }, coveredBy: [] })) };
  const json = report(files);

  test('opens quickly, and fills a long file as it scrolls', async () => {
    const page = await open(json);
    const ms = await page.eval(`(() => { const t = performance.now(); location.hash = '#/dir/src/main/java/com/example'; dispatchEvent(new HashChangeEvent('hashchange')); return performance.now() - t; })()`);
    assert.ok(ms < 500, `a folder of 1,000 files took ${ms.toFixed(0)} ms`);
    const t = Date.now();
    await page.route('#/file/src/main/java/com/example/Big.java');
    assert.ok(Date.now() - t < 3000, `the 30,000-line file took ${Date.now() - t} ms`);
    const first = await page.count('.ln');
    assert.ok(first < 6000, `${first} lines rendered at first: the rest wait until they're near`);
    await page.eval('scrollTo(0, document.documentElement.scrollHeight / 2)');
    await page.eval('new Promise(r => setTimeout(r, 200))');
    assert.ok(await page.count('.ln') > first, 'scrolling fills the lines it reaches');
    await page.eval(`dispatchEvent(new Event('beforeprint'))`);
    assert.equal(await page.count('.ln'), 30003, 'printing fills every line');
    assert.deepEqual(page.errors, []);
    await page.close();
  });
});
