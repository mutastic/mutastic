// Fails the build when the two editions' pages don't line up: a page the edition switch would open
// (src/edition-pages.mjs) that doesn't exist, a shared concept that doesn't exist or stands for a
// mutastic-rs page that doesn't, or a Rust page with no place in the Rust sidebar (LAYOUT in
// scripts/rust-docs.mjs). Either would otherwise pass unnoticed: the switch would fall
// back to an overview, and the page would sit under "More". It also fails when a mutastic-rs page
// a shared concept's Rust side was written from has changed since (src/shared-sources.json, see
// scripts/shared-sources.mjs), since the shared page may no longer say what it does. A release or
// CI build (DOCS_VERSION set) stops; a local one says what's wrong and carries on, so a page being
// written can be read.
import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  JAVA_ONLY_WAY,
  JAVA_OPERATORS,
  PAIRS,
  RUST_ONLY_WAY,
  SHARED,
} from '../src/edition-pages.mjs';
import { readManifest, staleSources } from './shared-sources.mjs';

const content = fileURLToPath(new URL('../src/content/docs/', import.meta.url));
const commands = fileURLToPath(new URL('../src/data/commands.json', import.meta.url));

/** Every page of the Java edition, and the pages both share, as paths: `concepts/results`. */
async function javaPages() {
  const paths = new Set(['', 'reference/environment', 'reference/api']);
  const walk = (under) => {
    for (const entry of readdirSync(join(content, under), { withFileTypes: true })) {
      const path = under === '' ? entry.name : `${under}/${entry.name}`;
      if (entry.isDirectory()) {
        if (path !== 'rust') walk(path);
      } else if (/\.mdx?$/.test(entry.name)) {
        paths.add(path.replace(/\.mdx?$/, '').replace(/(^|\/)index$/, ''));
      }
    }
  };
  walk('');
  if (existsSync(commands)) {
    const { default: model } = await import(commands, { with: { type: 'json' } });
    for (const command of model.commands) paths.add(`reference/${command.page}`);
  }
  return paths;
}

/**
 * @param {Set<string>} rust     every Rust page's path, `rust/concepts/unsafe`
 * @param {string[]} unplaced    Rust pages the Rust sidebar's layout doesn't list
 * @param {string[]} gone        mutastic-rs pages a shared page stands for that it doesn't have
 * @param {string} [dir]         the mutastic-rs docs folder the Rust pages were read from
 */
export async function checkEditions(rust, unplaced, gone = [], dir = undefined) {
  const java = await javaPages();
  const problems = [];
  const need = (pages, path, edition, why) => {
    if (!pages.has(path)) problems.push(`the ${edition} page '/${path}' ${why} doesn't exist`);
  };
  for (const [j, r] of PAIRS) {
    need(java, j, 'Java', `(paired with '/${r}' in src/edition-pages.mjs)`);
    need(rust, r, 'Rust', `(paired with '/${j}' in src/edition-pages.mjs)`);
  }
  for (const [j, r] of JAVA_ONLY_WAY) {
    need(java, j, 'Java', 'named in src/edition-pages.mjs');
    need(rust, r, 'Rust', 'named in src/edition-pages.mjs');
  }
  for (const [r, j] of RUST_ONLY_WAY) {
    need(rust, r, 'Rust', 'named in src/edition-pages.mjs');
    need(java, j, 'Java', 'named in src/edition-pages.mjs');
  }
  need(java, JAVA_OPERATORS, 'Java', "every Rust operator family's page opens");
  // A shared page is one page, at the Java edition's URL, so it needs no pair.
  for (const [page] of SHARED) need(java, page, 'shared', 'named in SHARED in src/edition-pages.mjs');
  for (const page of gone) {
    problems.push(`the Rust page '${page}' a shared page stands for (SHARED in src/edition-pages.mjs) isn't in mutastic-rs`);
  }
  for (const page of unplaced) {
    problems.push(`the Rust page '/${page}' has no place in the Rust sidebar: add it to LAYOUT in scripts/rust-docs.mjs`);
  }
  problems.push(...sharedSources(dir));
  if (problems.length === 0) return;
  const message = `The Java and Rust editions don't line up:\n  - ${problems.join('\n  - ')}`;
  if (process.env.DOCS_VERSION) throw new Error(message);
  console.warn(`[check-editions] ${message}`);
}

/** Each shared page whose Rust sources changed since it was written, or that has none recorded. */
function sharedSources(dir) {
  const manifest = readManifest();
  const shared = new Set(SHARED.map(([page]) => page));
  const problems = [];
  for (const page of shared) {
    if (!manifest[page]) problems.push(`the shared page '/${page}/' names no Rust sources in src/shared-sources.json`);
  }
  for (const page of Object.keys(manifest)) {
    if (!shared.has(page)) problems.push(`'${page}' in src/shared-sources.json isn't a shared page (SHARED in src/edition-pages.mjs)`);
  }
  if (dir === undefined) return problems;
  const stale = staleSources(dir).filter(({ page }) => shared.has(page));
  for (const { page, source, commit, gone } of stale) {
    const file = `docs/${source.split('#')[0]}`;
    const what = source.includes('#') ? `the section '#${source.split('#')[1]}' of ${file}` : file;
    const diff = commit ? `; see what changed: git -C ~/Code/mutastic-rs diff ${commit.slice(0, 7)} -- ${file}` : '';
    problems.push(
      gone
        ? `the shared page '/${page}/' was written from mutastic-rs's ${what}, which isn't there any more${diff}`
        : `the shared page '/${page}/' was written from mutastic-rs's ${what}, which has changed since${diff}`,
    );
  }
  if (stale.length > 0) {
    const pages = [...new Set(stale.map(({ page }) => page))].join(' ');
    problems.push(`once the shared page says what mutastic-rs now does, record its sources again: npm run stamp-shared -- ${pages}`);
  }
  return problems;
}
