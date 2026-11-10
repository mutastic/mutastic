// Fails the build when the two editions' pages don't line up: a page the edition switch would open
// (src/edition-pages.mjs) that doesn't exist, or a Rust page with no place in the Rust sidebar
// (LAYOUT in scripts/rust-docs.mjs). Either would otherwise pass unnoticed: the switch would fall
// back to an overview, and the page would sit under "More". A release or CI build (DOCS_VERSION
// set) stops; a local one says what's wrong and carries on, so a page being written can be read.
import { existsSync, readdirSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  JAVA_ONLY_WAY,
  JAVA_OPERATORS,
  PAIRS,
  RUST_ONLY_WAY,
} from '../src/edition-pages.mjs';

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
 * @param {Set<string>} rust     every Rust page's path, `rust/concepts/ids`
 * @param {string[]} unplaced    Rust pages the Rust sidebar's layout doesn't list
 */
export async function checkEditions(rust, unplaced) {
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
  for (const page of unplaced) {
    problems.push(`the Rust page '/${page}' has no place in the Rust sidebar: add it to LAYOUT in scripts/rust-docs.mjs`);
  }
  if (problems.length === 0) return;
  const message = `The Java and Rust editions don't line up:\n  - ${problems.join('\n  - ')}`;
  if (process.env.DOCS_VERSION) throw new Error(message);
  console.warn(`[check-editions] ${message}`);
}
