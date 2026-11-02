// Which release these docs describe, from the environment the build was given (see
// astro.config.mjs), and where every published release's docs are.

/** Where the latest release's docs are; an older line's are under `/v<line>/`. */
export const SITE = 'https://docs.mutastic.io/';

export const base = import.meta.env.BASE_URL;
export const version = process.env.DOCS_VERSION ?? '';
export const latest = process.env.DOCS_LATEST ?? '';
export const versions = (process.env.DOCS_VERSIONS ?? '').split(',').filter(Boolean);

/** Where a release line's docs are: the latest at the root, the others under their line. */
export function home(line: string): string {
  return line === latest ? SITE : `${SITE}v${line}/`;
}

/** The path of the page being read, without this build's base: `reference/run/`. */
export function pageWithin(pathname: string): string {
  return pathname.startsWith(base) ? pathname.slice(base.length) : pathname.replace(/^\//, '');
}
