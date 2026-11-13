// Whether the page being built is a shared page still waiting for its Rust side: AWAITING_RUST in
// src/edition-pages.mjs, as scripts/rust-docs.mjs found it for the Rust docs being built, in
// src/data/rust/awaiting.json. Such a page shows its Java side to both editions.
const written = import.meta.glob<string[]>('../data/rust/awaiting.json', {
  eager: true,
  import: 'default',
});
const awaiting: string[] = Object.values(written)[0] ?? [];

/**
 * Whether the page whose route is `locals.starlightRoute` waits for its Rust side. A page rendered
 * outside a route, as for /llms-full.txt, has no route to ask, and no edition: it shows its Java
 * side whatever this says.
 */
export function awaitsRust(locals: App.Locals): boolean {
  if (awaiting.length === 0) return false;
  try {
    return awaiting.includes(locals.starlightRoute.id);
  } catch {
    return false;
  }
}
