// The site's two editions, Java and Rust, and how a page in one finds its counterpart in the other.
//
// Every page belongs to one edition by its path: /rust/… is Rust, the coding agents' pages are
// shared by both, and the rest is Java. The sidebar shows one edition at a time (see
// route-editions.ts), and the switch at its top opens the same topic in the other edition when
// there is one, or that edition's overview when there isn't.

export type Edition = 'java' | 'rust' | 'shared';

/** A page's path under the site's base, without slashes at either end: `rust/concepts/ids`. */
export function pagePath(pathname: string, base: string): string {
  const root = base.endsWith('/') ? base : `${base}/`;
  const path = pathname.startsWith(root) ? pathname.slice(root.length) : pathname.replace(/^\//, '');
  return path.replace(/\/+$/, '').replace(/\.html$/, '');
}

export function editionOf(path: string): Edition {
  if (/^rust(\/|$)/.test(path)) return 'rust';
  if (/^agents(\/|$)/.test(path) || path === '404') return 'shared';
  return 'java';
}

/** The same topic in both editions. Each pair works both ways. */
const PAIRS: [java: string, rust: string][] = [
  ['', 'rust'],
  ['getting-started/install', 'rust/install'],
  ['getting-started/quickstart', 'rust/getting-started'],
  ['concepts/results', 'rust/concepts/results'],
  ['concepts/suggested-tests', 'rust/concepts/suggested-tests'],
  ['concepts/operators', 'rust/operators'],
  ['reference/run', 'rust/reference/cli'],
  ['reference/schemas', 'rust/reference/events'],
  ['guides/configuration', 'rust/reference/configuration'],
  ['guides/troubleshooting', 'rust/troubleshooting'],
];

/** Topics whose nearest counterpart is a page that maps back somewhere else. */
const JAVA_ONLY_WAY: [string, string][] = [['concepts/operators-advanced', 'rust/operators']];
const RUST_ONLY_WAY: [string, string][] = [
  ['rust/editions', 'getting-started/install'],
  ['rust/differences-from-java', ''],
];

const toRust = new Map<string, string>([...PAIRS, ...JAVA_ONLY_WAY]);
const toJava = new Map<string, string>([...PAIRS.map(([j, r]) => [r, j] as [string, string]), ...RUST_ONLY_WAY]);

function counterpart(path: string, edition: 'java' | 'rust'): string | undefined {
  if (edition === 'java') {
    const exact = toRust.get(path);
    if (exact !== undefined) return exact;
    // Every command's page has its counterpart in the Rust edition's one page of commands.
    if (/^reference\/(run|verify|explain|ignore|next|baseline|gate-[a-z]+|environment)$/.test(path)) {
      return 'rust/reference/cli';
    }
    return undefined;
  }
  const exact = toJava.get(path);
  if (exact !== undefined) return exact;
  if (/^rust\/operators\//.test(path)) return 'concepts/operators';
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
  if (edition === 'shared') return { java: { href: url('') }, rust: { href: url('rust') } };
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
