import { execFileSync } from 'node:child_process';

// Which release these docs describe, from the environment the build was given (see
// astro.config.mjs), and where every published release's docs are.

/** Where the latest release's docs are; an older line's are under `/v<line>/`. */
export const SITE = 'https://docs.mutastic.io/';

export const base = import.meta.env.BASE_URL;
export const version = process.env.DOCS_VERSION ?? '';
export const latest = process.env.DOCS_LATEST ?? '';
export const versions = (process.env.DOCS_VERSIONS ?? '').split(',').filter(Boolean);

/**
 * The release these docs tell people to install, `0.3.0`: the build's DOCS_RELEASE, which
 * scripts/build-versions.mjs sets from each tag, else the newest release tag in this checkout's
 * history. That's the tag itself on a release, and the latest release on main, whose docs
 * describe what's coming but install what's out. Never typed into a page, so a release can't ship
 * docs that install the one before it.
 */
export const release = process.env.DOCS_RELEASE ?? newestReleaseTag();

function newestReleaseTag(): string {
  const tag = execFileSync('git', ['describe', '--tags', '--abbrev=0', '--match', 'v[0-9]*'], {
    encoding: 'utf8',
  }).trim();
  return tag.replace(/^v/, '');
}

/** Where a release line's docs are: the latest at the root, the others under their line. */
export function home(line: string): string {
  return line === latest ? SITE : `${SITE}v${line}/`;
}

/** The path of the page being read, without this build's base: `reference/run/`. */
export function pageWithin(pathname: string): string {
  return pathname.startsWith(base) ? pathname.slice(base.length) : pathname.replace(/^\//, '');
}
