package io.mutastic.engine;

import io.mutastic.api.Status;
import io.mutastic.api.WatchedTest;
import java.util.function.Supplier;

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

  /// Whether the catch `caught` repeats each time its test runs `again` against the mutant, alone:
  /// false at the first time it passes. A catch, or a run of it again, that JUnit couldn't discover
  /// the test for is mutastic's own error, thrown: never a catch that didn't repeat, which would
  /// name the test likely flaky.
  boolean repeats(TestWorker.Run caught, Supplier<TestWorker.Run> again) {
    var catcher = caught.failedTest().orElseThrow();
    int times = timesFor(catcher);
    if (times > 0) {
      TestWorker.alone(catcher, caught);
    }
    for (int time = 0; time < times; time++) {
      if (TestWorker.alone(catcher, again.get()).status() == Status.MISSED) {
        return false;
      }
    }
    return true;
  }
}
