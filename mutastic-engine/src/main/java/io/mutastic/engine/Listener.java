package io.mutastic.engine;

import java.time.Duration;
import java.util.List;

/// Hears about a run as it happens, for progress and streamed output.
@FunctionalInterface
public interface Listener {
  /// The coverage pass is done: `mutants` will run (or be reported as skipped or not covered).
  ///
  /// @param jdk the feature version the tests run on
  /// @param flaky tests left out because they failed and then passed
  default void coverageFinished(int jdk, int tests, Duration took, int mutants, List<String> flaky) {}

  /// One mutant's result, as soon as it's known.
  void mutantFinished(MutantResult result);
}
