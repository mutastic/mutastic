// Builds docs.mutastic.io with every recent release's docs, each from its own tag, so each
// reference is written from its own release's CLI:
//
//   node scripts/build-versions.mjs --release   the latest release at the root and under
//                                               /v<line>/, and each earlier release line under
//                                               its own /v<line>/
//   node scripts/build-versions.mjs --next      this checkout alone, as main's docs: "next"
//
// Run from docs/, with the repository's tags fetched; the docs workflow does both. A release line
// is the newest patch of each minor version, 0.3 for 0.3.0 and 0.3.1. Older lines whose build
// fails are left out, and said so, rather than holding up a release; the latest must build.
import { execFileSync } from 'node:child_process';
import { cpSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';

/** How many release lines stay published, the latest first. */
const KEPT = 5;

const docs = resolve('.');
const repository = resolve('..');
const out = join(docs, 'dist');

function git(...args) {
  return execFileSync('git', args, { cwd: repository, encoding: 'utf8' }).trim();
}

/** `v0.3.1` → [0, 3, 1]. */
function numbers(tag) {
  return tag.slice(1).split('.').map(Number);
}

/** Whether a tag's docs can be built under a path of their own: they read DOCS_BASE. */
function versioned(tag) {
  try {
    return git('show', `${tag}:docs/astro.config.mjs`).includes('DOCS_BASE');
  } catch {
    return false; // no docs at that tag
  }
}

/** Each published release line and its newest tag, the latest first: [['0.5', 'v0.5.1'], …]. */
function releaseLines() {
  const tags = git('tag', '--list', 'v*')
    .split('\n')
    .filter((tag) => /^v\d+\.\d+\.\d+$/.test(tag))
    .sort((a, b) => {
      const [x, y] = [numbers(a), numbers(b)];
      return y[0] - x[0] || y[1] - x[1] || y[2] - x[2];
    });
  const lines = new Map();
  for (const tag of tags) {
    const [major, minor] = numbers(tag);
    const line = `${major}.${minor}`;
    if (!lines.has(line) && versioned(tag)) lines.set(line, tag);
  }
  return [...lines.entries()].slice(0, KEPT);
}

/** `npm run build` in `directory`, into `into`, told which release it is. */
function build(directory, into, environment) {
  execFileSync('npx', ['astro', 'build', '--outDir', into], {
    cwd: directory,
    stdio: 'inherit',
    env: { ...process.env, ...environment },
  });
}

/**
 * A release's docs checked under /vX.Y/ as at its root: 1.1.0's config ran the link checker under
 * every base, where links to the generated reference pages carry the base its exclusions don't
 * name, so its /v1.1/ build failed. Under a base, the checker is left out of a checkout whose
 * config runs it unguarded, as later configs do themselves: the root build has checked the same
 * pages.
 */
function linksCheckedAtRootOnly(worktree) {
  const config = join(worktree, 'docs', 'astro.config.mjs');
  const text = readFileSync(config, 'utf8');
  const unguarded = /\n(\s*)starlightLinksValidator\(\{[\s\S]*?\n\1\}\),\n/;
  if (text.includes("base === '/'") || !unguarded.test(text)) return;
  writeFileSync(config, text.replace(unguarded, '\n'));
}

/** The docs at `tag`, checked out on their own, their dependencies installed. */
function checkout(tag) {
  const worktree = mkdtempSync(join(tmpdir(), `mutastic-docs-${tag}-`));
  git('worktree', 'add', '--detach', worktree, tag);
  execFileSync('npm', ['ci', '--no-audit', '--no-fund'], {
    cwd: join(worktree, 'docs'),
    stdio: 'inherit',
  });
  return worktree;
}

function release() {
  const lines = releaseLines();
  if (lines.length === 0) throw new Error('no release has docs that can be versioned yet');
  const latest = lines[0][0];
  const shared = { DOCS_LATEST: latest, DOCS_VERSIONS: lines.map(([line]) => line).join(',') };
  rmSync(out, { recursive: true, force: true });
  const published = [];
  for (const [line, tag] of lines) {
    const worktree = checkout(tag);
    try {
      const environment = { ...shared, DOCS_VERSION: line, DOCS_RELEASE: tag.slice(1) };
      if (line === latest) {
        build(join(worktree, 'docs'), out, { ...environment, DOCS_BASE: '/' });
      }
      const staged = mkdtempSync(join(tmpdir(), `mutastic-docs-v${line}-`));
      linksCheckedAtRootOnly(worktree);
      build(join(worktree, 'docs'), staged, { ...environment, DOCS_BASE: `/v${line}/` });
      cpSync(staged, join(out, `v${line}`), { recursive: true });
      published.push({ line, tag, path: line === latest ? '/' : `/v${line}/` });
    } catch (error) {
      if (line === latest) throw error;
      console.warn(`mutastic docs: ${tag}'s docs didn't build, so ${line} is left out: ${error}`);
    } finally {
      git('worktree', 'remove', '--force', worktree);
    }
  }
  writeFileSync(join(out, 'versions.json'), JSON.stringify({ latest, published }, null, 2) + '\n');
  console.log(`mutastic docs: ${published.map((each) => each.tag).join(', ')}; ${latest} at the root`);
}

function next() {
  const lines = releaseLines();
  build(docs, out, {
    DOCS_BASE: '/',
    DOCS_VERSION: 'next',
    DOCS_LATEST: lines[0]?.[0] ?? '',
    DOCS_VERSIONS: lines.map(([line]) => line).join(','),
  });
}

// Said outright, so a mistake in the caller fails rather than quietly building the other.
const mode = process.argv[2];
if (mode === '--release') {
  release();
} else if (mode === '--next') {
  next();
} else {
  throw new Error(`build-versions: say --release or --next, not ${mode}`);
}
