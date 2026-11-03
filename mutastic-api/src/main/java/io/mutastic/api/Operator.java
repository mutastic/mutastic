package io.mutastic.api;

/// A kind of change mutastic makes: one of its own ([BuiltIn]), or one a plugin or rules file adds
/// ([Mutator]). How each finds its sites and rewrites them is the engine's, or the mutator's own.
///
/// Operators work on two kinds of site, counted apart: instructions (arithmetic, branches, returns)
/// and calls. A new operator never shifts the ids of existing mutants.
public sealed interface Operator permits BuiltIn, Mutator {
  /// `mutastic/boundary`, `stream/remove-stage`: how reports, rules and `--operators` name it.
  String id();

  /// What it changes, and when a test misses that, in plain words: for `mutastic explain`.
  String explanation();

  /// Whether it works on call sites rather than instruction sites.
  boolean onCalls();

  /// Which set of operators it's in: what runs unless `--operators` says otherwise.
  Tier tier();

  /// The sets operators come in. A run takes `defaults` unless told otherwise; `stronger` adds the
  /// operators that find more but cost more, or need more care to read.
  enum Tier {
    DEFAULTS,
    STRONGER;

    /// `defaults`, `stronger`: as `--operators` takes it.
    public String word() {
      return name().toLowerCase(java.util.Locale.ROOT);
    }
  }
}
