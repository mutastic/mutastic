// The Rust edition's pages, read from mutastic-rs when the site is built, and written under
// src/content/docs/rust/ for the build to pick up. That folder is ignored by git: the pages are
// never committed here, only this loader. Without the pages the site is built as it always is.
//
// Which mutastic-rs is read:
//   MUTASTIC_RS_DOCS=<dir>     that docs/ folder, e.g. a checkout being edited.
//   MUTASTIC_RS_DOCS=pinned    the release named in docs/rust-edition.json, fetched by its tag.
//   MUTASTIC_RS_DOCS=          (empty) none: the section is off.
//   unset, a local build       ~/Code/mutastic-rs/docs when it's there, else the pinned release.
//   unset, a release or CI     the pinned release, only once rust-edition.json says "publish":
//   build (DOCS_VERSION)       true. Until then the published site has no Rust section.
//
// The pin travels with each release's docs: a release line is rebuilt from its own tag, so it
// shows the Rust release it was published with. Move it with each Rust release.
//
// Its commands come as data, not pages: docs/commands.json, written from the Rust CLI's own
// declarations in the Java edition's shape, with each command's captured output in
// output/commands/. They're copied to src/data/rust/, also ignored by git, where
// src/pages/rust/reference/[page].astro writes a page for each command, as Java's are written.
// reference/commands.md, the same declarations as a page for GitHub, is left out: a link to it
// goes to the command's, option's or environment's place on those pages.
//
// The pages are Markdown that links to each other by relative path, with every block of captured
// output kept beside them in output/. Here each link becomes the site's URL for that page, and each
// captured block is read from output/, in the colour mutastic printed it (`.ansi`; a release
// from before colour was captured has `.txt`), and shown the way the other pages show theirs. The
// page's own copy of the block is the same output without colour, for GitHub.
//
// The concepts both editions share (SHARED in src/edition-pages.mjs) are the site's own pages,
// written once with edition tabs. The mutastic-rs page each stands for stays there, for GitHub,
// and is left out here: a link to it goes to the shared page, and the Rust sidebar lists the
// shared page in its place. The shared pages show the Rust edition's output from its captures,
// every block in output/, copied to src/data/rust/captures/ (see components/RustOutput.astro).
import { execFileSync } from 'node:child_process';
import { existsSync, mkdirSync, readdirSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { dirname, join, posix } from 'node:path';
import { fileURLToPath } from 'node:url';
import { SHARED } from '../src/edition-pages.mjs';

const target = fileURLToPath(new URL('../src/content/docs/rust/', import.meta.url));
/** Where the commands' data goes: commands.json, and each command's output in output/. */
const data = fileURLToPath(new URL('../src/data/rust/', import.meta.url));
/** Left in the folder this writes, so it never removes a folder it didn't write. */
const MARKER = '.written-by-rust-docs';
/** Where the section's pages are served, under the site's base. */
const SECTION = 'rust';
/** Each mutastic-rs page a shared page stands for, `concepts/results.md`, and the shared page's path. */
const SHARED_PAGES = new Map(SHARED.filter(([, rust]) => rust).map(([site, rust]) => [`${rust}.md`, site]));

const pinFile = fileURLToPath(new URL('../rust-edition.json', import.meta.url));
const cache = fileURLToPath(new URL('../node_modules/.cache/mutastic-rs/', import.meta.url));

/** The pinned release's docs/ folder, fetched by its tag the first time it's needed. */
function pinned() {
  const pin = JSON.parse(readFileSync(pinFile, 'utf8'));
  const into = join(cache, pin.ref.replace(/[^\w.-]/g, '_'));
  if (!existsSync(join(into, 'docs', 'README.md'))) {
    rmSync(into, { recursive: true, force: true });
    mkdirSync(cache, { recursive: true });
    const quiet = ['-c', 'advice.detachedHead=false'];
    execFileSync('git', [...quiet, 'clone', '--quiet', '--depth', '1', '--branch', pin.ref, pin.repository, into]);
    console.log(`[rust-docs] fetched mutastic-rs ${pin.ref}`);
  }
  return join(into, 'docs');
}

/** The mutastic-rs docs folder to read, or undefined when there's none. */
export function source() {
  const named = process.env.MUTASTIC_RS_DOCS;
  if (named === '') return undefined;
  if (named === 'pinned') return pinned();
  if (named !== undefined) return named.replace(/^~(?=\/|$)/, homedir());
  if (process.env.DOCS_VERSION) {
    if (!existsSync(pinFile)) return undefined;
    return JSON.parse(readFileSync(pinFile, 'utf8')).publish === true ? pinned() : undefined;
  }
  const local = join(homedir(), 'Code', 'mutastic-rs', 'docs');
  if (existsSync(join(local, 'README.md'))) return local;
  return existsSync(pinFile) ? pinned() : undefined;
}

/** The commands' reference as a page for GitHub; the site writes its own from commands.json. */
const COMMANDS_PAGE = 'reference/commands.md';

/** Every Markdown page under `dir`, as paths relative to it, captured output left out. */
function pages(dir, under = '') {
  return readdirSync(join(dir, under), { withFileTypes: true }).flatMap((entry) => {
    const path = under === '' ? entry.name : `${under}/${entry.name}`;
    if (entry.isDirectory()) return path === 'output' ? [] : pages(dir, path);
    return entry.name.endsWith('.md') && path !== COMMANDS_PAGE ? [path] : [];
  });
}

/** A captured output, `output/<name>.ansi` in colour, or `.txt` in a release from before that. */
function capture(dir, name) {
  return ['ansi', 'txt'].map((kind) => join(dir, 'output', `${name}.${kind}`)).find(existsSync);
}

/**
 * Where a link into commands.md goes on the site: `#run` to run's page, `#--report` to the option
 * on the first page that has it, `#environment` and `#exit-codes` to the environment's page.
 */
function commandsHref(anchor, commands) {
  const page = (name) => `/${SECTION}/reference/${name}/`;
  const command = commands?.commands.find((c) => c.page === anchor);
  if (command) return page(command.page);
  const option = commands?.options.find((o) => o.name === anchor);
  const first = commands?.commands.find((c) => option?.commands.includes(c.name));
  if (first) return `${page(first.page)}#${anchor.replace(/^--/, '')}`;
  if (anchor === 'environment' || anchor === 'exit-codes') return `${page('environment')}#${anchor}`;
  return page(commands?.commands[0]?.page ?? 'environment');
}

/** `concepts/ids.md` → `rust/concepts/ids`; `README.md` → `rust`. */
function slug(page) {
  const path = page.replace(/\.md$/, '').replace(/(^|\/)README$/, '');
  return path === '' ? SECTION : `${SECTION}/${path}`;
}

/** A link's target as the site's: other pages by their URL, docs.mutastic.io by its own path. */
function href(target, page, known, commands) {
  const site = target.match(/^https:\/\/docs\.mutastic\.io(\/.*)?$/);
  if (site) return site[1] ?? '/';
  if (/^[a-z]+:/i.test(target) || target.startsWith('#')) return target;
  const [path, anchor] = target.split('#');
  const resolved = posix.normalize(posix.join(posix.dirname(page), path));
  if (resolved === COMMANDS_PAGE && commands) return commandsHref(anchor ?? '', commands);
  if (!known.has(resolved)) return undefined; // a file in mutastic-rs, not a page
  const shared = SHARED_PAGES.get(resolved);
  if (shared) return `/${shared}/${anchor ? `#${anchor}` : ''}`;
  return `/${slug(resolved)}/${anchor ? `#${anchor}` : ''}`;
}

/** Markdown links, rewritten; a link to anything but a page keeps only its text. */
function links(line, page, known, commands) {
  return line.replace(/\[((?:`[^`]*`|[^\]`])*)\]\(([^)\s]+)\)/g, (_, text, target) => {
    const to = href(target, page, known, commands);
    return to === undefined ? text : `[${text}](${to})`;
  });
}

/** A page as the site's: its title in front matter, links and captured output as above. */
function convert(text, page, dir, known, commands) {
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
      const file = capture(dir, captured[1]);
      if (file !== undefined) {
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
    out.push(links(line, page, known, commands));
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
  // A page both editions share is listed by its mutastic-rs page, which it stands for, or by its
  // own path from the site's root when mutastic-rs has none: '/concepts/flaky-tests'.
  ['Concepts', [
    ['concepts/results', 'Results and the score'],
    ['concepts/suggested-tests', 'Suggested tests'],
    ['/concepts/equivalence', 'Equivalent mutants'],
    ['/concepts/flaky-tests', 'Flaky tests'],
    ['concepts/output', 'Output'],
    ['concepts/how-a-run-works', 'How a run works'],
    ['concepts/ids', 'Mutant ids'],
    ['concepts/next', 'Working through misses'],
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
    ['operators/library', 'Library methods'],
    ['operators/iterators', 'Iterators'],
    ['operators/options', 'Option and Result'],
    ['operators/control', 'Control flow'],
  ]],
  // A page for each command from commands.json comes first, then Environment and exit codes: see
  // reference() below.
  ['Reference', [['reference/reports', 'Report files'], ['reference/events', 'Events'], ['reference/configuration', 'Configuration'], ['reference/compatibility', 'Compatibility']]],
  ['Guides', [['/guides/ci', 'Running in CI']]],
  ['Help', [['troubleshooting', 'Troubleshooting'], ['differences-from-java', 'Differences from Java']]],
];

/** The pages written from commands.json, as the Java edition's sidebar lists its own. */
function reference(commands, base) {
  if (commands === undefined) return [];
  return [
    ...commands.commands.map((command) => ({
      label: `mutastic ${command.name}`,
      link: `${base}${SECTION}/reference/${command.page}/`,
    })),
    { label: 'Environment and exit codes', link: `${base}${SECTION}/reference/environment/` },
  ];
}

/** The paths of the pages written from commands.json: `rust/reference/run`. */
function commandPages(commands) {
  if (commands === undefined) return [];
  return [...commands.commands.map((command) => command.page), 'environment'].map(
    (page) => `${SECTION}/reference/${page}`,
  );
}

function sidebar(known, commands, base) {
  const listed = new Set();
  const groups = LAYOUT.map(([label, items]) => {
    const pages = items
      .filter(([path]) => path.startsWith('/') || known.has(path === '' ? 'README.md' : `${path}.md`))
      .map(([path, text]) => {
        if (path.startsWith('/')) return { label: text, slug: path.slice(1) };
        const page = path === '' ? 'README.md' : `${path}.md`;
        listed.add(page);
        const shared = SHARED_PAGES.get(page);
        return { label: text, slug: shared ?? (path === '' ? SECTION : `${SECTION}/${path}`) };
      });
    return { label, items: label === 'Reference' ? [...reference(commands, base), ...pages] : pages };
  });
  const rest = [...known].filter((page) => !listed.has(page) && !SHARED_PAGES.has(page)).sort();
  if (rest.length > 0) groups.push({ label: 'More', items: rest.map(slug) });
  return {
    group: { label: 'Rust', items: groups.filter((group) => group.items.length > 0) },
    unplaced: rest.map(slug),
  };
}

/** Removes a folder an earlier build wrote, and only one this wrote. */
function clear(folder) {
  if (!existsSync(folder)) return;
  if (!existsSync(join(folder, MARKER))) {
    throw new Error(`${folder} wasn't written by scripts/rust-docs.mjs; move it before building`);
  }
  rmSync(folder, { recursive: true });
}

/** Copies every captured output to src/data/rust/captures/, at its path under output/. */
function copyCaptures(dir, under = '') {
  const from = join(dir, 'output', under);
  if (!existsSync(from)) return;
  for (const entry of readdirSync(from, { withFileTypes: true })) {
    const path = under === '' ? entry.name : `${under}/${entry.name}`;
    if (entry.isDirectory()) copyCaptures(dir, path);
    else if (/\.(ansi|txt)$/.test(entry.name)) {
      mkdirSync(dirname(join(data, 'captures', path)), { recursive: true });
      writeFileSync(join(data, 'captures', path), readFileSync(join(from, entry.name)));
    }
  }
}

/** Copies commands.json and each command's captured output to src/data/rust/, when it's there. */
function copyCommands(dir) {
  const file = join(dir, 'commands.json');
  if (!existsSync(file)) return undefined;
  const commands = JSON.parse(readFileSync(file, 'utf8'));
  if (commands.schema !== 'mutastic.commands/v1') {
    throw new Error(`${file} is ${commands.schema}, and this site reads mutastic.commands/v1`);
  }
  mkdirSync(join(data, 'output'), { recursive: true });
  writeFileSync(join(data, 'commands.json'), JSON.stringify(commands, null, 2));
  for (const command of commands.commands) {
    const output = capture(dir, `commands/${command.page}`);
    if (output) writeFileSync(join(data, 'output', `${command.page}.ansi`), readFileSync(output));
  }
  writeFileSync(join(data, MARKER), `Written from ${dir} by scripts/rust-docs.mjs; ignored by git.\n`);
  return commands;
}

/**
 * Writes the Rust pages under src/content/docs/rust/, and its commands' data and captured output
 * under src/data/rust/, when there are any to read, and removes what an earlier build wrote when
 * there aren't. Returns the section's sidebar group, its pages' paths, any page the sidebar doesn't
 * place, the redirects from the Rust pages a shared page stands for, and any of those mutastic-rs
 * doesn't have, and the folder they were read from; or undefined when there are no Rust pages. `base` is the site's, which the
 * commands' links start with.
 */
export function rustDocs(base = '/') {
  clear(target);
  clear(data);
  const dir = source();
  if (dir === undefined || !existsSync(join(dir, 'README.md'))) return undefined;
  const known = new Set(pages(dir));
  const commands = copyCommands(dir);
  if (!existsSync(join(data, MARKER))) {
    mkdirSync(data, { recursive: true });
    writeFileSync(join(data, MARKER), `Written from ${dir} by scripts/rust-docs.mjs; ignored by git.\n`);
  }
  copyCaptures(dir);
  const written = [...known].filter((page) => !SHARED_PAGES.has(page));
  for (const page of written) {
    const into = join(target, page.replace(/(^|\/)README\.md$/, '$1index.md'));
    mkdirSync(dirname(into), { recursive: true });
    writeFileSync(into, convert(readFileSync(join(dir, page), 'utf8'), page, dir, known, commands).text);
  }
  writeFileSync(join(target, MARKER), `Written from ${dir} by scripts/rust-docs.mjs; ignored by git.\n`);
  console.log(`[rust-docs] ${written.length} Rust pages${commands ? ` and ${commands.commands.length} commands` : ''} from ${dir}`);
  const { group, unplaced } = sidebar(known, commands, base);
  // A Rust page a shared page stands for has the shared page's address, should it be linked.
  const redirects = Object.fromEntries(
    [...SHARED_PAGES].filter(([page]) => known.has(page)).map(([page, site]) => [`/${slug(page)}`, `${base}${site}/`]),
  );
  return {
    sidebar: group,
    pages: new Set([...written.map(slug), ...commandPages(commands)]),
    unplaced,
    redirects,
    shared: [...SHARED_PAGES.keys()].filter((page) => !known.has(page)),
    dir,
  };
}
