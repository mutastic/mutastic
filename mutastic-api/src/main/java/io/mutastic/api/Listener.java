package io.mutastic.api;

import java.util.List;

/// Hears about a run as it happens, for progress and streamed output.
@FunctionalInterface
@Internal
public interface Listener {
  /// The coverage pass is done, and the mutants are about to run.
  default void coverageFinished(CoveragePass pass) {}

  /// One mutant's result, as soon as it's known.
  void mutantFinished(MutantResult result);

  /// Every mutant in one source file's classes has its result: a top-level class and the classes
  /// nested in it, in the order found. Comes after their `mutantFinished` calls.
  default void fileFinished(String topLevelClass, List<MutantResult> results) {}

  /// A mutant's result again, after every mutant had its first result, without a test that turned
  /// out to fail on its own: for every mutant that test reached, caught or missed, as either result
  /// may rest on it. It carries `falseCatchBy` when that test was the one that caught it.
  default void mutantRevised(MutantResult result) {}
}
