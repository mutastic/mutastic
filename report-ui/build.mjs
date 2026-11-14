// Builds the HTML report page both editions embed: src/ (markup, styles, script) and fonts/, into
// one self-contained report.html with an empty slot for mutation.json, and report.html.sha256.
// Nothing is fetched: the page opens offline, from disk or from a CI artifact.
//
//   node report-ui/build.mjs            write report.html and its checksum
//   node report-ui/build.mjs --check    fail if either is out of date with src/ and fonts/
//   node report-ui/build.mjs --embed mutation.json out.html [--prompt]
//                                       a page with that report in it, to look at while working
//
// An edition embeds the report by replacing SLOT, which the page holds once, with the same element
// holding the JSON, every `<` written `<` (see embed): the JSON can then never end the script
// element or open a comment in it, and JSON.parse reads it back unchanged.
import { createHash } from 'node:crypto';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
export const SLOT = '<script type="application/json" id="mutation-report"></script>';
export const PAGE = join(HERE, 'report.html');
export const CHECKSUM = join(HERE, 'report.html.sha256');

const FONTS = [
  ['Bricolage Grotesque', 'bricolage-grotesque-latin-wght-normal.woff2', '200 800'],
  ['JetBrains Mono', 'jetbrains-mono-latin-400-normal.woff2', '400'],
  ['JetBrains Mono', 'jetbrains-mono-latin-700-normal.woff2', '700'],
];

function read(path) {
  return readFileSync(join(HERE, path), 'utf8');
}

function fonts() {
  const faces = FONTS.map(([family, file, weight]) => {
    const data = readFileSync(join(HERE, 'fonts', file)).toString('base64');
    return `@font-face{font-family:'${family}';font-style:normal;font-weight:${weight};font-display:swap;src:url(data:font/woff2;base64,${data}) format('woff2')}`;
  });
  return '/* Bricolage Grotesque, Copyright 2022 The Bricolage Grotesque Project Authors, and JetBrains Mono,\n' +
    '   Copyright 2020 The JetBrains Mono Project Authors: SIL Open Font License 1.1 (scripts.sil.org/OFL) */\n' + faces.join('\n');
}

/// The page. `prompt` turns on Copy as prompt.
export function build({ prompt = false } = {}) {
  let js = read('src/report.js');
  for (const bad of ['</script', '<!--']) {
    if (js.includes(bad) || read('src/report.css').includes(bad)) throw new Error(`the script and styles can't hold ${bad}`);
  }
  if (prompt) {
    js = once(js, 'var PROMPT = false;', 'var PROMPT = true;');
  }
  let page = read('src/report.html');
  page = once(page, '/*{{fonts}}*/', fonts());
  page = once(page, '/*{{css}}*/', read('src/report.css').trimEnd());
  page = once(page, '/*{{js}}*/', js.trimEnd());
  page = once(page, '<!--{{slot}}-->', SLOT);
  if (page.split(SLOT).length !== 2) throw new Error('the page must hold the slot once');
  return page;
}

function once(text, marker, value) {
  const at = text.indexOf(marker);
  if (at < 0 || text.indexOf(marker, at + 1) >= 0) throw new Error(`${marker} must appear once`);
  return text.slice(0, at) + value + text.slice(at + marker.length);
}

export function sha256(text) {
  return createHash('sha256').update(text, 'utf8').digest('hex');
}

/// The page with `json`, a mutation.json's text, in its slot: what each edition does.
export function embed(page, json) {
  JSON.parse(json);
  const filled = '<script type="application/json" id="mutation-report">' + json.trim().replace(/</g, '\\u003c') + '</script>';
  return page.split(SLOT).join(filled);
}

function main(args) {
  if (args[0] === '--embed') {
    const [, from, to] = args;
    writeFileSync(to, embed(build({ prompt: args.includes('--prompt') }), readFileSync(from, 'utf8')));
    return;
  }
  const page = build();
  const sum = `${sha256(page)}  report.html\n`;
  if (args[0] === '--check') {
    const stale = [];
    if (readFileSync(PAGE, 'utf8') !== page) stale.push('report.html');
    if (readFileSync(CHECKSUM, 'utf8') !== sum) stale.push('report.html.sha256');
    if (stale.length) {
      console.error(`${stale.join(' and ')} ${stale.length > 1 ? 'are' : 'is'} out of date with src/ and fonts/: run node report-ui/build.mjs and commit the result.`);
      process.exit(1);
    }
    console.log(`report.html is up to date (${(page.length / 1024).toFixed(0)} KB, sha256 ${sha256(page)}).`);
    return;
  }
  writeFileSync(PAGE, page);
  writeFileSync(CHECKSUM, sum);
  console.log(`wrote report.html (${(page.length / 1024).toFixed(0)} KB) and report.html.sha256`);
}

if (process.argv[1] && fileURLToPath(import.meta.url) === process.argv[1]) {
  main(process.argv.slice(2));
}
