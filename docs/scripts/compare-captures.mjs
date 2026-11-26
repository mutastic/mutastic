// Compares each edition's captured output with the reference edition's, scenario by scenario,
// after masking what each run and each language says its own way: ids, directories, paths, file
// locations, timings, source lines, suggested tests' code, test and function names, the phases a
// run may or may not time, and the summary's engine rows (the unit, Build, the toolchain). What's
// left is labels, order, punctuation and fixed text, which should be the same. The scenarios are
// paired in public/contract/captures.json; the contract (public/contract/v1.json) names the
// reference, and each edition's binding gives where its captures are and the words its masks take
// (`masks`, `sources`), so an edition joins with its binding, not an edit here.
//
// A pair that differs fails unless a difference in the contract names that edition (or is a slot,
// `each`) and has the item `capture:<scenario>`; a registered one that now matches fails too, so
// the list only shrinks. Run it with
// `node scripts/compare-captures.mjs [mutastic-rs docs folder]` for every problem.
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { differencesOf, load } from './contract.mjs';

const root = fileURLToPath(new URL('../../', import.meta.url));
const mapping = fileURLToPath(new URL('../public/contract/captures.json', import.meta.url));

/** A capture by its name, `.ansi` in colour or `.txt`, or undefined. */
function read(dir, name) {
  const file = ['ansi', 'txt'].map((kind) => join(dir, `${name}.${kind}`)).find(existsSync);
  return file && readFileSync(file, 'utf8');
}

/** Every capture's name under `dir/under`, without its extension. */
function names(dir, under) {
  if (!existsSync(join(dir, under))) return [];
  return readdirSync(join(dir, under), { recursive: true })
    .filter((name) => /\.(ansi|txt)$/.test(name))
    .map((name) => `${under}/${name.replace(/\.(ansi|txt)$/, '')}`);
}

/** Where `edition`'s captures are: its binding's path, under its docs folder `docs`. */
function capturesOf(contract, edition, docs) {
  const path = contract.editions[edition].captures;
  return edition === contract.reference ? join(root, path) : join(docs, path.replace(/^docs\//, ''));
}

/** The pairs to compare for `edition`: the scenarios named, and the captures at the same path in `samePath`. */
export function pairs(dir, edition = 'rust', contract = load()) {
  const { scenarios, samePath } = JSON.parse(readFileSync(mapping, 'utf8'));
  const reference = contract.reference;
  const paired = Object.entries(scenarios)
    .filter(([, named]) => named[edition] !== undefined)
    .map(([scenario, named]) => [scenario, named[reference], named[edition]]);
  // As it always has, this looks for `under` in the edition's docs folder, not its captures, so
  // `operators` pairs nothing yet: each language's operator examples are its own code.
  for (const under of samePath) {
    const found = new Set(names(dir, under));
    for (const name of names(capturesOf(contract, reference, dir), under)) if (found.has(name)) paired.push([name, name, name]);
  }
  return paired;
}

const escape = (text) => text.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
const any = (words) => `(?:${words.join('|')})`;

/** The masks, built from every edition's binding: the words each says its own way. */
export function masks(contract = load()) {
  const bindings = Object.values(contract.editions);
  const word = (key, quote = false) => any(bindings.map((b) => (quote ? escape(b.masks[key]) : b.masks[key])));
  const extensions = any(bindings.flatMap((b) => b.sources.extensions));
  const code = any(['assert', '}', '// in ', ...bindings.flatMap((b) => b.masks.code)].map(escape));
  return {
    file: new RegExp(`[\\w./-]+\\.${extensions}(:\\d+){0,2}\\b`, 'g'),
    work: new RegExp(`\\b${word('work', true)}\\b`, 'g'),
    toolchain: new RegExp(`\\bon ${word('toolchain')}`, 'g'),
    failed: new RegExp(`^(\\s*)It ${word('failed')}: .*`),
    tests: new RegExp(`\\bin ${word('tests')}\\b`, 'g'),
    function: new RegExp(`\\b${word('function')}s?\\b`, 'g'),
    unit: new RegExp(`\\b${word('unit', true)}  `),
    level: new RegExp(`^(\\s*)= ${word('level', true)}\\b.*`),
    code: new RegExp(`^(\\s*)${code}`),
  };
}

/** The line as it reads once what each edition or run says its own way is masked. */
export function mask(line, m = masks()) {
  const plain = line.replace(/\x1b\[[0-9;]*m/g, '').replace(/\x1b\]8;;[^\x1b]*\x1b\\/g, '');
  const event = plain.match(/^\{"schema":"mutastic\.events\/v1".*?"reason":"(?<reason>[\w-]+)"/);
  if (event) return `<event ${event.groups.reason}>`;
  const masked = plain
    .replace(/\bm[0-9a-f]{8}\b/g, '<id>')
    .replace(/\b[0-9a-f]{7,40}\b/g, '<commit>')
    .replace(/\b(samples|fixtures)\/[\w.-]+/g, '<dir>')
    .replace(/(^|\s)\/\w[^\s`'"]*/g, '$1<path>')
    .replace(m.work, '<work>')
    .replace(m.file, '<file>')
    // The phases a run times only where it has them, as the contract's Duration row says.
    .replace(/\b(build|checking) \d+(\.\d+)?(ms|s), /g, '')
    .replace(/\b\d+(\.\d+)?(ms|s)\b/g, '<t>')
    .replace(/\b\d\d:\d\d:\d\d\b/g, '<clock>')
    .replace(m.toolchain, 'on <toolchain>')
    .replace(m.failed, '$1It failed: <failure>')
    .replace(m.tests, 'in <tests>')
    .replace(/\b[A-Z]\w*(\.|::)\w+\(\)/g, '<name>()')
    .replace(/[\w.]+#\w+|\w+(::\w+)+/g, '<name>')
    .replace(m.function, '<fn>')
    .replace(m.unit, '<unit>  ')
    .replace(/\bworkers?\b/g, 'worker')
    .replace(m.level, '$1= <level>')
    .replace(/\d+/g, '<n>');
  if (/^ *<n> │/.test(masked)) return '<source>';
  if (/^\s*(= mutant\s+)?[-+] /.test(masked)) return '<change>';
  if (m.code.test(masked)) return '<code>';
  // camelCase and snake_case read alike, and spacing is each language's.
  return masked.replace(/_/g, '').toLowerCase().replace(/\s+/g, ' ').trimEnd();
}

/** A capture's lines, masked, with each run of code or source lines as one, and engine rows gone. */
export function shape(text, m = masks()) {
  const lines = [];
  for (const line of text.split('\n')) {
    if (/^\s*Build  /.test(line.replace(/\x1b\[[0-9;]*m/g, ''))) continue;
    const masked = mask(line, m);
    if (['<code>', '<source>', '<change>'].includes(masked) && lines.at(-1) === masked) continue;
    lines.push(masked);
  }
  while (lines.at(-1) === '') lines.pop();
  while (lines[0] === '') lines.shift();
  return lines;
}

/** Each pair of `edition`'s whose shape differs from the reference's, with its first differing line. */
export function compareCaptures(dir, edition = 'rust', contract = load()) {
  const differ = [];
  const same = [];
  const m = masks(contract);
  const reference = capturesOf(contract, contract.reference, dir);
  const theirs = capturesOf(contract, edition, dir);
  for (const [scenario, j, r] of pairs(dir, edition, contract)) {
    const [left, right] = [read(reference, j), read(theirs, r)];
    if (left === undefined || right === undefined) {
      differ.push({ scenario, missing: left === undefined ? `${contract.reference} ${j}` : `${edition} ${r}` });
      continue;
    }
    const [a, b] = [shape(left, m), shape(right, m)];
    const at = a.findIndex((line, index) => line !== b[index]);
    const first = at === -1 && a.length !== b.length ? Math.min(a.length, b.length) : at;
    if (first === -1) same.push(scenario);
    else differ.push({ scenario, line: first + 1, reference: a[first] ?? '<end>', edition: b[first] ?? '<end>' });
  }
  return { compared: same.length + differ.filter((d) => !d.missing).length, same, differ };
}

/** The scenarios the differences naming `edition` register, each with the difference's id. */
export function registered(contract, edition) {
  const by = new Map();
  for (const d of differencesOf(contract, edition)) {
    for (const item of d.items ?? []) if (item.startsWith('capture:')) by.set(item.slice('capture:'.length), d.id);
  }
  return by;
}

/** What fails for `edition`: a pair that differs unregistered, a registered one that matches, one with no capture. */
export function captureProblems(dir, edition = 'rust', contract = load()) {
  const { differ, same } = compareCaptures(dir, edition, contract);
  const known = registered(contract, edition);
  const problems = [];
  for (const d of differ) {
    if (d.missing) {
      problems.push(`the scenario ${d.scenario} has no capture to pair: ${d.missing} (public/contract/captures.json)`);
    } else if (!known.has(d.scenario)) {
      problems.push(
        `the ${edition} capture ${d.scenario} differs from ${contract.reference}'s at line ${d.line}:\n      ${contract.reference}: ${d.reference}\n      ${edition}: ${d.edition}\n    make them match, or record it in public/contract/v1.json: a difference naming ${edition}, with the item capture:${d.scenario} and its reason`,
      );
    }
  }
  for (const scenario of same) {
    if (known.has(scenario)) {
      problems.push(`${known.get(scenario)} now matches for ${edition}'s capture ${scenario}: take capture:${scenario} out of its items in public/contract/v1.json`);
    }
  }
  return problems;
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const dir = process.argv[2] ?? join(process.env.HOME, 'Code', 'mutastic-rs', 'docs');
  const problems = captureProblems(dir);
  if (problems.length > 0) {
    console.error(`The editions' captures don't line up:\n  - ${problems.join('\n  - ')}`);
    process.exitCode = 1;
  } else {
    const { compared } = compareCaptures(dir);
    console.log(`Each of the ${compared} paired captures has the same shape, or a recorded difference.`);
  }
}
