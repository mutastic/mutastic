package io.mutastic.engine;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/// One mutant's outcome.
///
/// @param coveredBy the tests that reach the mutant's line, fastest first
/// @param caughtBy the test that failed, if one did
/// @param failure what it failed with: an assertion, or the code under test throwing
/// @param testsRun how many covering tests ran before the result was known
public record MutantResult(
    Mutant mutant,
    Status status,
    List<String> coveredBy,
    Optional<String> caughtBy,
    Optional<String> failure,
    int testsRun,
    Duration took) {

  public MutantResult {
    coveredBy = List.copyOf(coveredBy);
  }

  static MutantResult notCovered(Mutant mutant) {
    return new MutantResult(mutant, Status.NOT_COVERED, List.of(), Optional.empty(), Optional.empty(), 0, Duration.ZERO);
  }

  static MutantResult skipped(Mutant mutant) {
    return new MutantResult(mutant, Status.SKIPPED, List.of(), Optional.empty(), Optional.empty(), 0, Duration.ZERO);
  }
}
