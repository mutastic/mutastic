package io.mutastic.engine;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/// One mutant's outcome.
///
/// @param coveredBy the tests that reach the mutant's line, fastest first
/// @param caughtBy the test that failed, if one did
/// @param failure what it failed with: the exception's class
/// @param failureOrigin where that came from: `assertion` (a check failed), `test` (the test's
///     own code threw) or `code` (the code under test threw)
/// @param testsRun how many covering tests ran before the result was known
/// @param unconfirmed a test whose catch didn't repeat when run again, if catches were confirmed
/// @param inputs the whole numbers the covering tests passed to the mutant's method, by
///     parameter name: what the tests tried, so what they didn't is plain
/// @param suggestion a call whose result the mutant changes, for a missed mutant, if one was found
public record MutantResult(
    Mutant mutant,
    Status status,
    List<String> coveredBy,
    Optional<String> caughtBy,
    Optional<String> failure,
    Optional<String> failureOrigin,
    int testsRun,
    Optional<String> unconfirmed,
    java.util.Map<String, List<Long>> inputs,
    Optional<Suggestion> suggestion,
    Duration took) {

  public MutantResult {
    coveredBy = List.copyOf(coveredBy);
    inputs = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(inputs));
  }

  static MutantResult notCovered(Mutant mutant) {
    return new MutantResult(mutant, Status.NOT_COVERED, List.of(), Optional.empty(), Optional.empty(), Optional.empty(), 0,
        Optional.empty(), java.util.Map.of(), Optional.empty(), Duration.ZERO);
  }

  static MutantResult skipped(Mutant mutant) {
    return new MutantResult(mutant, Status.SKIPPED, List.of(), Optional.empty(), Optional.empty(), Optional.empty(), 0,
        Optional.empty(), java.util.Map.of(), Optional.empty(), Duration.ZERO);
  }

  /// The same result with a suggested test.
  MutantResult withSuggestion(Optional<Suggestion> found) {
    return new MutantResult(mutant, status, coveredBy, caughtBy, failure, failureOrigin, testsRun, unconfirmed, inputs, found,
        took);
  }
}
