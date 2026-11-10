// The Rust edition's pages, read from a checkout of mutastic-rs when the site is built, and written
// under src/content/docs/rust/ for the build to pick up. That folder is ignored by git: the pages
// are never committed here, only this loader. Without the pages the site is built as it always is.
//
//   MUTASTIC_RS_DOCS   the docs/ folder of a mutastic-rs checkout. Empty turns the section off.
//                      Unset, ~/Code/mutastic-rs/docs is read when it exists, and only for a local
//                      build: one told it's a release (DOCS_VERSION) reads it only when it's named.
//
// The pages are Markdown that links to each other by relative path, with every block of captured
// output kept beside them in output/. Here each link becomes the site's URL for that page, and each
// captured block is read from output/ and shown the way the other pages show theirs.
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, posix } from 'node:path';
import { fileURLToPath } from 'node:url';

const target = fileURLToPath(new URL('../src/content/docs/rust/', import.meta.url));
/** Left in the folder this writes, so it never removes a folder it didn't write. */
const MARKER = '.written-by-rust-docs';
/** Where the section's pages are served, under the site's base. */
const SECTION = 'rust';

/** The mutastic-rs docs folder to read, or undefined when there's none. */
function source() {
  const named = process.env.MUTASTIC_RS_DOCS;
  if (named !== undefined) return named === '' ? undefined : named.replace(/^~(?=\/|$)/, homedir());
  if (process.env.DOCS_VERSION) return undefined;
  return join(homedir(), 'Code', 'mutastic-rs', 'docs');
}

/** Every Markdown page under `dir`, as paths relative to it, captured output left out. */
function pages(dir, under = '') {
  return readdirSync(join(dir, under), { withFileTypes: true }).flatMap((entry) => {
    const path = under === '' ? entry.name : `${under}/${entry.name}`;
    if (entry.isDirectory()) return path === 'output' ? [] : pages(dir, path);
    return entry.name.endsWith('.md') ? [path] : [];
  });
}

/** `concepts/ids.md` → `rust/concepts/ids`; `README.md` → `rust`. */
function slug(page) {
  const path = page.replace(/\.md$/, '').replace(/(^|\/)README$/, '');
  return path === '' ? SECTION : `${SECTION}/${path}`;
}

/** A link's target as the site's: other pages by their URL, docs.mutastic.io by its own path. */
function href(target, page, known) {
  const site = target.match(/^https:\/\/docs\.mutastic\.io(\/.*)?$/);
  if (site) return site[1] ?? '/';
  if (/^[a-z]+:/i.test(target) || target.startsWith('#')) return target;
  const [path, anchor] = target.split('#');
  const resolved = posix.normalize(posix.join(posix.dirname(page), path));
  if (!known.has(resolved)) return undefined; // a file in mutastic-rs, not a page
  return `/${slug(resolved)}/${anchor ? `#${anchor}` : ''}`;
}

/** Markdown links, rewritten; a link to anything but a page keeps only its text. */
function links(line, page, known) {
  return line.replace(/\[((?:`[^`]*`|[^\]`])*)\]\(([^)\s]+)\)/g, (_, text, target) => {
    const to = href(target, page, known);
    return to === undefined ? text : `[${text}](${to})`;
  });
}

/** A page as the site's: its title in front matter, links and captured output as above. */
function convert(text, page, dir, known) {
  const lines = text.split('\n');
  const heading = lines.findIndex((line) => line.startsWith('# '));
  const title = heading === -1 ? page : lines[heading].slice(2).replaceAll('`', '').trim();
  if (heading !== -1) lines.splice(heading, 1);
  const out = [];
  let fence; // the fence of the code block being copied, if any
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    if (fence !== undefined) {
      out.push(line);
      if (line.trim() === fence) fence = undefined;
      continue;
    }
    const captured = line.match(/^<!-- output: (\S+) -->$/);
    const opens = lines[i + 1]?.match(/^(`{3,})/);
    if (captured && opens) {
      const file = join(dir, 'output', `${captured[1]}.txt`);
      if (existsSync(file)) {
        // The copy in output/ is what mutastic printed; the block in the page is checked against it.
        const end = lines.findIndex((l, j) => j > i + 1 && l.trim() === opens[1]);
        const body = readFileSync(file, 'utf8').replace(/\n$/, '');
        const ticks = '`'.repeat(Math.max(3, ...[...body.matchAll(/`+/g)].map((m) => m[0].length + 1)));
        out.push(`${ticks}ansi`, body, ticks);
        i = end === -1 ? lines.length : end;
        continue;
      }
    }
    const open = line.match(/^\s*(`{3,}|~{3,})/);
    if (open) fence = open[1];
    out.push(links(line, page, known));
  }
  const front = ['---', `title: ${JSON.stringify(title)}`, 'editUrl: false', '---', ''];
  return { title, text: front.join('\n') + out.join('\n') };
}

/**
 * The Rust edition's sidebar: the same groups, in the same order and under the same names, as the
 * Java edition's in astro.config.mjs, and the same label wherever a page is the same topic
 * ("Install", "Quickstart", "Results and the score"). A page this doesn't list, a new one in
 * mutastic-rs, is still shown, under "More", until it's given its place here.
 */
const LAYOUT = [
  ['Getting started', [['', 'Overview'], ['install', 'Install'], ['getting-started', 'Quickstart'], ['editions', 'Java and Rust together']]],
  ['Concepts', [
    ['concepts/results', 'Results and the score'],
    ['concepts/suggested-tests', 'Suggested tests'],
    ['concepts/how-a-run-works', 'How a run works'],
    ['concepts/ids', 'Mutant ids'],
    ['concepts/unsafe', 'Unsafe code'],
    ['concepts/profiles', 'Profiles'],
  ]],
  ['Operators', [
    ['operators', 'All operators'],
    ['operators/conditions', 'Conditions'],
    ['operators/arithmetic', 'Arithmetic'],
    ['operators/returns', 'Returns and errors'],
    ['operators/calls', 'Calls and writes'],
    ['operators/arguments', 'Arguments'],
    ['operators/options', 'Option and Result'],
    ['operators/iterators', 'Iterators'],
    ['operators/control', 'Match and loops'],
    ['operators/library', 'Standard library'],
  ]],
  ['Reference', [['reference/cli', 'Commands'], ['reference/configuration', 'Configuration'], ['reference/events', 'Events']]],
  ['Help', [['troubleshooting', 'Troubleshooting'], ['differences-from-java', 'Differences from Java']]],
];

function sidebar(known) {
  const listed = new Set();
  const groups = LAYOUT.map(([label, items]) => ({
    label,
    items: items
      .filter(([path]) => known.has(path === '' ? 'README.md' : `${path}.md`))
      .map(([path, text]) => {
        listed.add(path === '' ? 'README.md' : `${path}.md`);
        return { label: text, slug: path === '' ? SECTION : `${SECTION}/${path}` };
      }),
  }));
  const rest = [...known].filter((page) => !listed.has(page)).sort();
  if (rest.length > 0) groups.push({ label: 'More', items: rest.map(slug) });
  return { label: 'Rust', items: groups.filter((group) => group.items.length > 0) };
}

/**
 * Writes the Rust pages under src/content/docs/rust/ when there are any to read, and removes what
 * an earlier build wrote when there aren't. Returns the section's sidebar group, or undefined.
 */
export function rustDocs() {
  if (existsSync(target)) {
    if (!existsSync(join(target, MARKER))) {
      throw new Error(`${target} wasn't written by scripts/rust-docs.mjs; move it before building`);
    }
    rmSync(target, { recursive: true });
  }
  const dir = source();
  if (dir === undefined || !existsSync(join(dir, 'README.md'))) return undefined;
  const known = new Set(pages(dir));
  for (const page of known) {
    const into = join(target, page.replace(/(^|\/)README\.md$/, '$1index.md'));
    mkdirSync(dirname(into), { recursive: true });
    writeFileSync(into, convert(readFileSync(join(dir, page), 'utf8'), page, dir, known).text);
  }
  writeFileSync(join(target, MARKER), `Written from ${dir} by scripts/rust-docs.mjs; ignored by git.\n`);
  console.log(`[rust-docs] ${known.size} Rust pages from ${dir}`);
  return sidebar(known);
}
