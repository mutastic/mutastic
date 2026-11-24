package io.mutastic.engine;

import io.mutastic.api.WatchedTest;

/// How many more times a catch is run against its mutant before it counts: a catch that doesn't
/// repeat came from a flaky test, and the mutant's other covering tests decide.
///
/// @param catches whether every catch is confirmed once (`--confirm-catches`)
/// @param history what earlier runs say of the tests: a watched test's every catch is confirmed
///   [WatchedTest#CONFIRMATIONS] times, whatever `catches` says
record Confirming(boolean catches, FlakyHistory history) {
  /// For a catch by `test`: [WatchedTest#CONFIRMATIONS] for a watched test, once with
  /// `--confirm-catches`, else none.
  int timesFor(String test) {
    return history.watched().contains(test) ? WatchedTest.CONFIRMATIONS : catches ? 1 : 0;
  }
}
