// Gives each page the sidebar of its own edition. The sidebar is configured as a `Java` group and
// a `Rust` group, then the groups both editions share. A Java page gets the Java group's contents
// and the shared groups; a Rust page the same with Rust's. A shared page (the coding agents') gets
// both, and the reader's last edition picks which is shown (components/Sidebar.astro). Previous
// and next stay within the page's own edition.
//
// With only the Java edition's pages, its group is opened out and nothing else changes.
import { defineRouteMiddleware } from '@astrojs/starlight/route-data';
import { editionOf, pagePath, switchLinks, type Edition, type SwitchLink } from './editions';

type Entry = App.Locals['starlightRoute']['sidebar'][number];
type Link = Extract<Entry, { type: 'link' }>;

export interface EditionPanes {
  edition: Edition;
  java: Entry[];
  rust: Entry[];
  shared: Entry[];
  links: Record<'java' | 'rust', SwitchLink>;
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
  const edition = rust === undefined ? 'java' : editionOf(path);

  const own = edition === 'rust' ? rust!.entries : edition === 'java' ? java.entries : shared;
  route.sidebar = [...(edition === 'rust' ? rust!.entries : java.entries), ...shared];

  // Previous and next, from the page's own edition only, keeping a page's own say over them.
  const links = flatten(own);
  const at = links.findIndex((link) => link.isCurrent);
  const data = route.entry.data as { prev?: unknown; next?: unknown };
  route.pagination = {
    prev: data.prev === false || at <= 0 ? undefined : links[at - 1],
    next: data.next === false || at === -1 || at >= links.length - 1 ? undefined : links[at + 1],
  };

  if (rust === undefined) return;
  (context.locals as { editionPanes?: EditionPanes }).editionPanes = {
    edition,
    java: java.entries,
    rust: rust.entries,
    shared,
    links: switchLinks(path, import.meta.env.BASE_URL),
  };
});
