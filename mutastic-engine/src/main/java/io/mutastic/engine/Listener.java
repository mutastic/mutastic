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

  /// Every mutant in one source file's classes has its result: a top-level class and the
  /// classes nested in it, in the order found. Comes after their `mutantFinished` calls.
  default void fileFinished(String topLevelClass, List<MutantResult> results) {}
}
