// The site's two editions, Java and Rust, and how a page in one finds its counterpart in the other.
//
// Every page belongs to one edition by its path: /rust/… is Rust, the coding agents' pages and the
// concepts written once for both (SHARED in edition-pages.mjs) are shared, and the rest is Java. The sidebar shows one edition at a time (see
// route-editions.ts), and the switch at its top opens the same topic in the other edition when
// there is one, or that edition's overview when there isn't.

import {
  JAVA_ONLY_WAY,
  JAVA_OPERATORS,
  PAIRS,
  RUST_ONLY_WAY,
  RUST_OPERATORS,
  SHARED,
} from './edition-pages.mjs';

export type Edition = 'java' | 'rust' | 'shared';

/** A page's path under the site's base, without slashes at either end: `rust/concepts/ids`. */
export function pagePath(pathname: string, base: string): string {
  const root = base.endsWith('/') ? base : `${base}/`;
  const path = pathname.startsWith(root) ? pathname.slice(root.length) : pathname.replace(/^\//, '');
  return path.replace(/\/+$/, '').replace(/\.html$/, '');
}

const sharedPages = new Set<string>(SHARED.map(([page]) => page as string));

/** Whether the page is one both editions share: a coding agents' page or a shared concept. */
export function isShared(path: string): boolean {
  return /^agents(\/|$)/.test(path) || path === '404' || sharedPages.has(path);
}

export function editionOf(path: string): Edition {
  if (/^rust(\/|$)/.test(path)) return 'rust';
  if (isShared(path)) return 'shared';
  return 'java';
}

const toRust = new Map<string, string>([...PAIRS, ...JAVA_ONLY_WAY] as [string, string][]);
const toJava = new Map<string, string>([
  ...(PAIRS as [string, string][]).map(([j, r]) => [r, j] as [string, string]),
  ...(RUST_ONLY_WAY as [string, string][]),
]);

function counterpart(path: string, edition: 'java' | 'rust'): string | undefined {
  if (edition === 'java') {
    return toRust.get(path);
  }
  const exact = toJava.get(path);
  if (exact !== undefined) return exact;
  if (RUST_OPERATORS.test(path)) return JAVA_OPERATORS;
  return undefined;
}

export interface SwitchLink {
  href: string;
  /** Said when the other edition has no page on this topic, so the switch opens its overview. */
  note?: string;
}

/** Where each side of the switch goes from this page. */
export function switchLinks(path: string, base: string): Record<'java' | 'rust', SwitchLink> {
  const root = base.replace(/\/$/, '');
  const url = (to: string) => `${root}/${to === '' ? '' : `${to}/`}`;
  const edition = editionOf(path);
  // On a shared page the switch changes the sidebar and the tabs in place; without script, a
  // shared concept stays where it is, and an agents' page opens the edition's overview.
  if (edition === 'shared') {
    if (sharedPages.has(path)) return { java: { href: url(path) }, rust: { href: url(path) } };
    return { java: { href: url('') }, rust: { href: url('rust') } };
  }
  const here = { href: url(path) };
  const there = counterpart(path, edition);
  const other: SwitchLink =
    there === undefined
      ? {
          href: url(edition === 'java' ? 'rust' : ''),
          note: `The ${edition === 'java' ? 'Rust' : 'Java'} edition has no page on this topic: this opens its overview.`,
        }
      : { href: url(there) };
  return edition === 'java' ? { java: here, rust: other } : { java: other, rust: here };
}
