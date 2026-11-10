// The mutastic-rs pages each shared concept's Rust side was written from, and a fingerprint of
// each as it was then: src/shared-sources.json. The shared pages' prose about the Rust edition is
// the site's own, so when one of those pages changes in mutastic-rs, the shared page may no longer
// say what it does. scripts/check-editions.mjs compares the fingerprints with the Rust docs being
// built; `npm run stamp-shared` records them again once the shared page is brought up to date.
//
// A source is a page, `concepts/results.md`, or one section of it, `troubleshooting.md#a-test-is-flaky`
// (the heading's GitHub anchor, up to the next heading of its level or above). Its fingerprint
// leaves out what a re-capture alone changes: every block of captured output (the `<!-- output: -->`
// marker stays, so adding or removing one still counts), and any timing (`1.3s`, `4ms`,
// `"durationMs":93`) left in the page's other blocks.
import { createHash } from 'node:crypto';
import { existsSync, readFileSync } from 'node:fs';
import { join } from 'node:path';
import { fileURLToPath } from 'node:url';

export const manifestFile = fileURLToPath(new URL('../src/shared-sources.json', import.meta.url));

/** The manifest: `{ "concepts/results": { commit, sources: { "concepts/results.md": "<hash>" } } }`. */
export function readManifest() {
  return JSON.parse(readFileSync(manifestFile, 'utf8'));
}

/** A heading's anchor as GitHub writes it: `A \`?\` that fails` → `a--that-fails`. */
function anchor(heading) {
  return heading
    .trim()
    .toLowerCase()
    .replace(/[^\p{L}\p{N}\s_-]/gu, '')
    .replace(/\s/g, '-');
}

/** The section under the heading whose anchor is `name`, or undefined when there's none. */
function section(lines, name) {
  let fence;
  let start;
  let level;
  for (let i = 0; i < lines.length; i++) {
    const open = lines[i].match(/^\s*(`{3,}|~{3,})/);
    if (fence !== undefined) {
      if (lines[i].trim() === fence) fence = undefined;
      continue;
    }
    if (open) {
      fence = open[1];
      continue;
    }
    const heading = lines[i].match(/^(#{1,6})\s+(.*)$/);
    if (!heading) continue;
    if (start !== undefined && heading[1].length <= level) return lines.slice(start, i);
    if (start === undefined && anchor(heading[2]) === name) {
      start = i;
      level = heading[1].length;
    }
  }
  return start === undefined ? undefined : lines.slice(start);
}

/** The lines with each captured block left out but its marker, and timings blanked. */
function withoutCaptures(lines) {
  const out = [];
  for (let i = 0; i < lines.length; i++) {
    out.push(lines[i]);
    const captured = /^<!-- output: \S+ -->$/.test(lines[i]);
    const opens = lines[i + 1]?.match(/^(`{3,}|~{3,})/);
    if (captured && opens) {
      const end = lines.findIndex((l, j) => j > i + 1 && l.trim() === opens[1]);
      i = end === -1 ? lines.length : end;
    }
  }
  return out.map((line) =>
    line
      .replace(/("durationMs"\s*:\s*)\d+/g, '$1#')
      .replace(/\b\d+(\.\d+)?(ms|s)\b/g, '#')
      .trimEnd(),
  );
}

/**
 * The fingerprint of `source` (`page.md` or `page.md#anchor`) in the docs folder `dir`, or
 * undefined when the page, or the section, isn't there.
 */
export function fingerprint(dir, source) {
  const [page, name] = source.split('#');
  const file = join(dir, page);
  if (!existsSync(file)) return undefined;
  let lines = readFileSync(file, 'utf8').replace(/\r\n/g, '\n').split('\n');
  if (name !== undefined) lines = section(lines, name);
  if (lines === undefined) return undefined;
  const text = withoutCaptures(lines).join('\n').trim();
  return createHash('sha256').update(text).digest('hex').slice(0, 16);
}

/**
 * Each source whose fingerprint differs from the manifest's, or that's gone:
 * `[{ page, source, commit, gone }]`.
 */
export function staleSources(dir) {
  const stale = [];
  for (const [page, { commit, sources }] of Object.entries(readManifest())) {
    for (const [source, recorded] of Object.entries(sources)) {
      const now = fingerprint(dir, source);
      if (now !== recorded) stale.push({ page, source, commit, gone: now === undefined });
    }
  }
  return stale;
}
