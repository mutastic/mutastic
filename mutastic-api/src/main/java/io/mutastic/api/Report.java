package io.mutastic.api;

import java.time.Duration;
import java.util.List;
import java.util.OptionalDouble;

/// A finished run: every mutant's result, in the order they were found.
///
/// @param jdk the feature version the tests ran on
/// @param flaky tests that failed in the coverage pass and passed when run again: left out of every
///   mutant's run, so their catches don't count
/// @param coverage how long the coverage pass took, of `took`
/// @param workers how many workers ran side by side
/// @param started how many worker JVMs started: one per worker, plus one after each kill
public record Report(
    String project,
    int jdk,
    List<MutantResult> results,
    int tests,
    List<String> flaky,
    Duration coverage,
    int workers,
    int started,
    Duration took) {

  public Report {
    results = List.copyOf(results);
    flaky = List.copyOf(flaky);
  }

  /// Workers started again after one was killed for taking too long.
  public int replaced() {
    return started - workers;
  }

  public long count(Status status) {
    return results.stream().filter(result -> result.status() == status).count();
  }

  /// The mutants that ran or could have: all but the skipped ones.
  public long scored() {
    return results.size() - count(Status.SKIPPED);
  }

  /// Detected mutants as a share of the scored ones, from 0 to 100; none when nothing was scored,
  /// which is no evidence either way, not a perfect score.
  public OptionalDouble score() {
    return scored() == 0
        ? OptionalDouble.empty()
        : OptionalDouble.of(
            100.0
                * results.stream().filter(result -> result.status().detected()).count()
                / scored());
  }

  /// At or above `threshold`, or nothing to score: with no mutants there is nothing to fail.
  public boolean passes(int threshold) {
    return score().orElse(100) >= threshold;
  }
}
