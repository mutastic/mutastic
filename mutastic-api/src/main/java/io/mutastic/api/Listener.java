package io.mutastic.api;

import java.time.Duration;
import java.util.List;

/// Hears about a run as it happens, for progress and streamed output.
@FunctionalInterface
@Internal
public interface Listener {
  /// The mutants are found, `mutants` of them, and the coverage pass is about to start.
  default void mutantsFound(int mutants) {}

  /// How far the coverage pass is: `done` tests have run of the `planned`, which is 0 until the
  /// tests are known.
  default void coverageProgress(int done, int planned) {}

  /// The coverage pass starts `test`, by its id.
  default void testStarted(String test) {}

  /// The coverage pass is done, and the mutants are about to run.
  default void coverageFinished(CoveragePass pass) {}

  /// The mutants will run on `workers` side by side, and should take `expected` between them: each
  /// what its covering tests took in the coverage pass. Comes after `coverageFinished`.
  default void mutantsPlanned(int workers, Duration expected) {}

  /// Worker `worker`, counted from 0, starts `mutant`, which should take what `cost` says. A mutant
  /// no test reaches isn't said to start.
  default void mutantStarted(int worker, Mutant mutant, MutantCost cost) {}

  /// Worker `worker` has finished what it was running, with a result or to run it again later.
  default void workerIdle(int worker) {}

  /// One mutant's result, as soon as it's known.
  void mutantFinished(MutantResult result);

  /// Every mutant in one source file's classes has its result: a top-level class and the classes
  /// nested in it, in the order found. Comes after their `mutantFinished` calls.
  default void fileFinished(String topLevelClass, List<MutantResult> results) {}

  /// A mutant's result again, after every mutant had its first result, without a test that turned
  /// out to fail on its own: for every mutant that test reached, caught or missed, as either result
  /// may rest on it. It carries `falseCatchBy` when that test was the one that caught it.
  default void mutantRevised(MutantResult result) {}

  /// Every mutant has its first result, and what comes after them is under way: `step`, with `done`
  /// of its `total` done, or both 0 for a step that isn't counted.
  default void checking(String step, int done, int total) {}
}
