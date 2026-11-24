package io.mutastic.engine;

import io.mutastic.api.WatchedTest;
import java.util.Map;
import java.util.Set;

/// What earlier runs say about the tests' catches (decision 27): the last comparable run's results,
/// when the code, the tests and mutastic are what it had, so a mutant whose result changed points
/// at the test that caught it; and the tests watched since a run named them likely flaky, whose
/// every catch is confirmed [WatchedTest#CONFIRMATIONS] more times.
///
/// @param sameCode the last comparable run's results by mutant id, only when nothing it ran has
///   changed since: empty otherwise, as a result that changed is then just the change at work
/// @param watched the tests in `last-runs/flaky-watch.tsv`
public record FlakyHistory(Map<String, RunRecord.Entry> sameCode, Set<String> watched) {
  /// Nothing known from earlier runs: the first run, or one that keeps no record.
  public static final FlakyHistory NONE = new FlakyHistory(Map.of(), Set.of());

  public FlakyHistory {
    sameCode = Map.copyOf(sameCode);
    watched = Set.copyOf(watched);
  }

  /// How many more times a catch by `test` is run against its mutant before it counts:
  /// [WatchedTest#CONFIRMATIONS] for a watched test, once with `--confirm-catches`, else none.
  int confirmationsOf(String test, boolean confirmCatches) {
    return watched.contains(test) ? WatchedTest.CONFIRMATIONS : confirmCatches ? 1 : 0;
  }
}
