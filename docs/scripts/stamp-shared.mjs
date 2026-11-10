// Records again the fingerprints of the mutastic-rs pages a shared concept was written from
// (src/shared-sources.json), once the shared page says what they say: see scripts/shared-sources.mjs.
//
//   npm run stamp-shared                          every shared page
//   npm run stamp-shared -- concepts/results      only that one
//
// It reads the mutastic-rs docs a local build reads (MUTASTIC_RS_DOCS, else ~/Code/mutastic-rs/docs),
// and records that checkout's commit with each page it stamps, for the next `git diff`. To add or
// drop a source, edit its page's "sources" in the manifest, then stamp it.
import { execFileSync } from 'node:child_process';
import { writeFileSync } from 'node:fs';
import { fingerprint, manifestFile, readManifest } from './shared-sources.mjs';
import { source } from './rust-docs.mjs';
import { SHARED } from '../src/edition-pages.mjs';

const dir = source();
if (dir === undefined) {
  console.error('[stamp-shared] no mutastic-rs docs to read: set MUTASTIC_RS_DOCS to its docs/ folder');
  process.exit(1);
}
const git = (...args) => execFileSync('git', ['-C', dir, ...args], { encoding: 'utf8' }).trim();
let commit;
try {
  commit = git('rev-parse', 'HEAD');
} catch {
  console.warn(`[stamp-shared] ${dir} isn't in a git checkout: no commit recorded`);
}

const manifest = readManifest();
const shared = new Set(SHARED.map(([page]) => page));
const only = process.argv.slice(2);
for (const page of only) {
  if (!manifest[page]) {
    console.error(`[stamp-shared] '${page}' isn't in src/shared-sources.json`);
    process.exit(1);
  }
}
let failed = false;
for (const [page, entry] of Object.entries(manifest)) {
  if (!shared.has(page)) {
    console.error(`[stamp-shared] '${page}' isn't a shared page (SHARED in src/edition-pages.mjs)`);
    failed = true;
    continue;
  }
  if (only.length > 0 && !only.includes(page)) continue;
  const files = [];
  for (const src of Object.keys(entry.sources)) {
    const now = fingerprint(dir, src);
    if (now === undefined) {
      console.error(`[stamp-shared] ${page}: '${src}' isn't in ${dir}`);
      failed = true;
      continue;
    }
    if (now !== entry.sources[src]) console.log(`[stamp-shared] ${page}: ${src} ${entry.sources[src] || '(new)'} → ${now}`);
    entry.sources[src] = now;
    files.push(src.split('#')[0]);
  }
  if (commit) {
    // A source changed and not committed isn't in the commit recorded: the next diff misses it.
    const dirty = git('status', '--porcelain', '--', ...new Set(files));
    if (dirty) console.warn(`[stamp-shared] ${page}: uncommitted in mutastic-rs, so not in ${commit.slice(0, 7)}:\n${dirty}`);
  }
  entry.commit = commit ?? null;
}
if (failed) process.exit(1);
writeFileSync(manifestFile, `${JSON.stringify(manifest, null, 2)}\n`);
console.log(`[stamp-shared] stamped from ${dir}${commit ? ` at ${commit.slice(0, 7)}` : ''}`);
