// Compares the two editions' captured output, scenario by scenario, after masking what each run
// and each language says its own way: ids, directories, paths, file locations, timings, source
// lines, suggested tests' code, test and function names, and the summary's engine rows (the unit,
// Build, the toolchain). What's left is labels, order, punctuation and fixed text, which should be
// the same. The scenarios are paired in public/schemas/contract/captures.json, and the editions'
// contract (public/schemas/contract/v1.json) is what holds the rest of their output level.
//
// It warns, never fails, while the masks are still finding their shape: run it with
// `node scripts/compare-captures.mjs [mutastic-rs docs folder]` for every difference.
import { existsSync, readdirSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

const java = fileURLToPath(new URL('../src/data/output/', import.meta.url));
const mapping = fileURLToPath(new URL('../public/schemas/contract/captures.json', import.meta.url));

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

/** The pairs to compare: the scenarios named, and the captures at the same path in `samePath`. */
export function pairs(rust) {
  const { scenarios, samePath } = JSON.parse(readFileSync(mapping, 'utf8'));
  const paired = Object.entries(scenarios).map(([scenario, { java: j, rust: r }]) => [scenario, j, r]);
  for (const under of samePath) {
    const theirs = new Set(names(rust, under));
    for (const name of names(java, under)) if (theirs.has(name)) paired.push([name, name, name]);
  }
  return paired;
}

const CODE = /^(\s*)(@Test|#\[test\]|void |fn |public |pub |assert|let |var |int |}|\/\/ in |use |import |mod |#\[cfg)/;

/** The line as it reads once what each edition or run says its own way is masked. */
export function mask(line) {
  const plain = line.replace(/\x1b\[[0-9;]*m/g, '').replace(/\x1b\]8;;[^\x1b]*\x1b\\/g, '');
  const event = plain.match(/^\{"schema":"mutastic\.events\/v1".*?"reason":"(?<reason>[\w-]+)"/);
  if (event) return `<event ${event.groups.reason}>`;
  const masked = plain
    .replace(/\bm[0-9a-f]{8}\b/g, '<id>')
    .replace(/\b[0-9a-f]{7,40}\b/g, '<commit>')
    .replace(/\b(samples|fixtures)\/[\w.-]+/g, '<dir>')
    .replace(/(^|\s)\/\w[^\s`'"]*/g, '$1<path>')
    .replace(/\b(build|target)\/mutastic\b/g, '<work>')
    .replace(/[\w./-]+\.(java|kt|rs)(:\d+){0,2}\b/g, '<file>')
    .replace(/\b\d+(\.\d+)?(ms|s)\b/g, '<t>')
    .replace(/\b\d\d:\d\d:\d\d\b/g, '<clock>')
    .replace(/\bon (JDK \d+|rustc [\d.]+)/g, 'on <toolchain>')
    .replace(/^(\s*)It (failed|panicked): .*/, '$1It failed: <failure>')
    .replace(/\bin (\w+Test|mod tests)\b/g, 'in <tests>')
    .replace(/\b[A-Z]\w*(\.|::)\w+\(\)/g, '<name>()')
    .replace(/[\w.]+#\w+|\w+(::\w+)+/g, '<name>')
    .replace(/\b(method|function)s?\b/g, '<fn>')
    .replace(/\b(Classes|Files)  /, '<unit>  ')
    .replace(/\bworkers?\b/g, 'worker')
    .replace(/^(\s*)= (bytecode|change)\b.*/, '$1= <level>')
    .replace(/\d+/g, '<n>');
  if (/^ *<n> │/.test(masked)) return '<source>';
  if (/^\s*(= mutant\s+)?[-+] /.test(masked)) return '<change>';
  if (CODE.test(masked)) return '<code>';
  // camelCase and snake_case read alike, and spacing is each language's.
  return masked.replace(/_/g, '').toLowerCase().replace(/\s+/g, ' ').trimEnd();
}

/** A capture's lines, masked, with each run of code or source lines as one, and engine rows gone. */
export function shape(text) {
  const lines = [];
  for (const line of text.split('\n')) {
    if (/^\s*Build  /.test(line.replace(/\x1b\[[0-9;]*m/g, ''))) continue;
    const masked = mask(line);
    if (['<code>', '<source>', '<change>'].includes(masked) && lines.at(-1) === masked) continue;
    lines.push(masked);
  }
  while (lines.at(-1) === '') lines.pop();
  while (lines[0] === '') lines.shift();
  return lines;
}

/** Each pair whose shapes differ, with its first differing line from each edition. */
export function compareCaptures(rust) {
  const differ = [];
  let compared = 0;
  for (const [scenario, j, r] of pairs(rust)) {
    const [left, right] = [read(java, j), read(join(rust, 'output'), r)];
    if (left === undefined || right === undefined) {
      differ.push({ scenario, missing: left === undefined ? `java ${j}` : `rust ${r}` });
      continue;
    }
    compared++;
    const [a, b] = [shape(left), shape(right)];
    const at = a.findIndex((line, index) => line !== b[index]);
    const first = at === -1 && a.length !== b.length ? Math.min(a.length, b.length) : at;
    if (first !== -1) differ.push({ scenario, line: first + 1, java: a[first] ?? '<end>', rust: b[first] ?? '<end>' });
  }
  return { compared, differ };
}

/** What to warn about: how many scenarios match, and each that doesn't. */
export function captureWarnings(rust) {
  const { compared, differ } = compareCaptures(rust);
  if (differ.length === 0) return [];
  return [
    `${compared - differ.filter((d) => !d.missing).length} of ${compared} paired captures have the same shape in both editions (public/schemas/contract/captures.json); these don't:`,
    ...differ.map((d) =>
      d.missing
        ? `  ${d.scenario}: no capture ${d.missing}`
        : `  ${d.scenario}, line ${d.line}:\n      java: ${d.java}\n      rust: ${d.rust}`,
    ),
  ];
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  const rust = process.argv[2] ?? join(process.env.HOME, 'Code', 'mutastic-rs', 'docs');
  console.log(captureWarnings(rust).join('\n') || 'Every paired capture has the same shape.');
}
