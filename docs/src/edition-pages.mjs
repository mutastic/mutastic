// Which page in one edition is the same topic in the other: the edition switch opens it (see
// editions.ts), and the build fails when a page named here doesn't exist (scripts/check-editions.mjs).
// Paths are under the site's base, without slashes: '' is the Java edition's overview.

/**
 * The concepts both editions share: one page each, at the Java edition's URL, written once with
 * edition tabs only where the editions differ. Both sidebars list them, and on one the switch
 * changes the sidebar and the tabs in place. Each names the mutastic-rs page it stands for, when
 * there is one: the site leaves that page out (it stays in mutastic-rs, for GitHub) and sends the
 * Rust pages' links to it here. A shared page needs no pair.
 *
 * The mutastic-rs pages each one's Rust side was written from are in src/shared-sources.json,
 * with a fingerprint of each: the build says when one has changed since (scripts/shared-sources.mjs).
 */
export const SHARED = [
  ['concepts/results', 'concepts/results'],
  ['concepts/suggested-tests', 'concepts/suggested-tests'],
  ['concepts/equivalence', undefined],
  ['concepts/flaky-tests', undefined],
  ['concepts/output', 'concepts/output'],
  ['concepts/how-a-run-works', 'concepts/how-a-run-works'],
  ['concepts/ids', 'concepts/ids'],
  ['concepts/test-first', undefined],
  ['guides/ci', undefined],
];

/** The same topic in both editions. Each pair works both ways. */
export const PAIRS = [
  ['', 'rust'],
  ['getting-started/install', 'rust/install'],
  ['getting-started/quickstart', 'rust/getting-started'],
  ['concepts/operators', 'rust/operators'],
  // The operator families: the same in both, but streams and Optional, which are iterators and
  // Option and Result in Rust.
  ['concepts/operators/conditions', 'rust/operators/conditions'],
  ['concepts/operators/arithmetic', 'rust/operators/arithmetic'],
  ['concepts/operators/returns', 'rust/operators/returns'],
  ['concepts/operators/calls', 'rust/operators/calls'],
  ['concepts/operators/arguments', 'rust/operators/arguments'],
  ['concepts/operators/library', 'rust/operators/library'],
  ['concepts/operators/streams', 'rust/operators/iterators'],
  ['concepts/operators/optional', 'rust/operators/options'],
  ['concepts/operators/control', 'rust/operators/control'],
  // A page for each command, written in both from the CLI's own declarations. next and baseline
  // are the Java edition's only, and mutants the Rust edition's: the switch opens the overview.
  ['reference/run', 'rust/reference/run'],
  ['reference/verify', 'rust/reference/verify'],
  ['reference/explain', 'rust/reference/explain'],
  ['reference/ignore', 'rust/reference/ignore'],
  ['reference/gate-open', 'rust/reference/gate-open'],
  ['reference/gate-check', 'rust/reference/gate-check'],
  ['reference/gate-close', 'rust/reference/gate-close'],
  ['reference/environment', 'rust/reference/environment'],
  ['reference/reports', 'rust/reference/reports'],
  ['reference/schemas', 'rust/reference/events'],
  ['guides/configuration', 'rust/reference/configuration'],
  ['guides/troubleshooting', 'rust/troubleshooting'],
];

/** Topics whose nearest counterpart is a page that maps back somewhere else. */
export const JAVA_ONLY_WAY = [];
export const RUST_ONLY_WAY = [
  ['rust/editions', 'getting-started/install'],
  ['rust/differences-from-java', ''],
];

/** Each Rust operator family's page has its counterpart in the Java edition's operators page. */
export const RUST_OPERATORS = /^rust\/operators\//;
export const JAVA_OPERATORS = 'concepts/operators';
