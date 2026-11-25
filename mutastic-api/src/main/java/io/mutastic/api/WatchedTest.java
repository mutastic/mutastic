package io.mutastic.api;

/// A test an earlier run named likely flaky, remembered in `last-runs/flaky-watch.tsv`: each catch
/// it makes counts only if it repeats [#CONFIRMATIONS] more times, until it has held for [#NEEDED]
/// full runs in a row (decision 27).
///
/// @param held full runs in a row, this one included when it's one, in which it caught mutants and
///   every catch held
/// @param released whether this run made it [#NEEDED], so it's forgotten after it
@Internal
public record WatchedTest(String test, int held, boolean released) {
  /// How many more times each catch a watched test makes is run against its mutant. A test that
  /// fails 1 time in 3 on its own, as one did on a real project, makes a false catch that counts 1
  /// time in 3 unchecked, 1 in 9 confirmed once, and 1 in 81 confirmed 3 times.
  public static final int CONFIRMATIONS = 3;

  /// How many full runs in a row its catches must hold before it's forgotten: a false catch holds
  /// [#CONFIRMATIONS] confirmations 1 time in 27, so 3 such runs by luck alone is 1 in about
  /// 20,000.
  public static final int NEEDED = 3;
}
