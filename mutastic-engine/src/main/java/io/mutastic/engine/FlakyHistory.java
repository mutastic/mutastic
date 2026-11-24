package io.mutastic.engine;

import java.util.Map;
import java.util.Set;

/// What earlier runs say about the tests' catches (decision 27): the last comparable run's results,
/// when the code, the tests and mutastic are what it had, so a mutant whose result changed points
/// at the test that caught it; and the tests watched since a run named them likely flaky, whose
/// every catch is confirmed [io.mutastic.api.WatchedTest#CONFIRMATIONS] more times.
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
}
