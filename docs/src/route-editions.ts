// Gives each page the sidebar of its own edition. The sidebar is configured as a `Java` group and
// a `Rust` group, then the groups both editions share. A Java page gets the Java group's contents
// and the shared groups; a Rust page the same with Rust's. A shared page (the coding agents') gets
// both, and the reader's last edition picks which is shown (components/Sidebar.astro). Previous
// and next stay within the page's own edition; a concept both share has each edition's, and the
// reader's edition shows one (components/Pagination.astro).
//
// With only the Java edition's pages, its group is opened out and nothing else changes.
import { defineRouteMiddleware } from '@astrojs/starlight/route-data';
import { editionOf, pagePath, switchLinks, type Edition, type SwitchLink } from './editions';

type Entry = App.Locals['starlightRoute']['sidebar'][number];
type Link = Extract<Entry, { type: 'link' }>;
type Pagination = App.Locals['starlightRoute']['pagination'];

export interface EditionPanes {
  edition: Edition;
  java: Entry[];
  rust: Entry[];
  shared: Entry[];
  links: Record<'java' | 'rust', SwitchLink>;
  /** On a concept both editions share, previous and next in each edition's sidebar. */
  pagination?: Record<'java' | 'rust', Pagination>;
}

const group = (entries: Entry[], label: string) =>
  entries.find((entry) => entry.type === 'group' && entry.label === label) as
    | Extract<Entry, { type: 'group' }>
    | undefined;

const flatten = (entries: Entry[]): Link[] =>
  entries.flatMap((entry) => (entry.type === 'group' ? flatten(entry.entries) : [entry]));

export const onRequest = defineRouteMiddleware((context) => {
  const route = context.locals.starlightRoute;
  const java = group(route.sidebar, 'Java');
  if (java === undefined) return;
  const rust = group(route.sidebar, 'Rust');
  const shared = route.sidebar.filter((entry) => entry !== java && entry !== rust);
  const path = pagePath(context.url.pathname, import.meta.env.BASE_URL);
  // Starlight marks only the first link to this page as current; a shared concept is in both
  // editions' groups, and is current in each.
  const every = flatten(route.sidebar);
  const current = every.find((link) => link.isCurrent)?.href;
  for (const link of every) if (link.href === current) link.isCurrent = true;
  const edition = rust === undefined ? 'java' : editionOf(path);

  route.sidebar = [...(edition === 'rust' ? rust!.entries : java.entries), ...shared];

  // Previous and next, from the page's own edition only, keeping a page's own say over them.
  const data = route.entry.data as { prev?: unknown; next?: unknown };
  const around = (entries: Entry[]): Pagination | undefined => {
    const links = flatten(entries);
    const at = links.findIndex((link) => link.isCurrent);
    if (at === -1) return undefined;
    return {
      prev: data.prev === false || at === 0 ? undefined : links[at - 1],
      next: data.next === false || at === links.length - 1 ? undefined : links[at + 1],
    };
  };
  const none: Pagination = { prev: undefined, next: undefined };
  const inJava = around(java.entries);
  const inRust = rust === undefined ? undefined : around(rust.entries);
  const own =
    edition === 'rust' ? inRust : edition === 'java' ? inJava : (around(shared) ?? inJava ?? inRust);
  route.pagination = own ?? none;

  if (rust === undefined) return;
  (context.locals as { editionPanes?: EditionPanes }).editionPanes = {
    edition,
    java: java.entries,
    rust: rust.entries,
    shared,
    links: switchLinks(path, import.meta.env.BASE_URL),
    pagination:
      edition === 'shared' && inJava !== undefined && inRust !== undefined
        ? { java: inJava, rust: inRust }
        : undefined,
  };
});
