package io.mutastic.engine;

import java.time.Duration;
import java.util.List;

/// A finished run: every mutant's result, in the order they were found.
///
/// @param jdk the feature version the tests ran on
/// @param flaky tests that failed in the coverage pass and passed when run again: left out of
///     every mutant's run, so their catches don't count
public record Report(String project, int jdk, List<MutantResult> results, int tests, List<String> flaky, Duration took) {

  public Report {
    results = List.copyOf(results);
    flaky = List.copyOf(flaky);
  }

  public long count(Status status) {
    return results.stream().filter(result -> result.status() == status).count();
  }

  /// The mutants that ran or could have: all but the skipped ones.
  public long scored() {
    return results.size() - count(Status.SKIPPED);
  }

  /// Detected mutants as a share of the scored ones, from 0 to 100; 100 when there are none.
  public double score() {
    return scored() == 0 ? 100 : 100.0 * results.stream().filter(result -> result.status().detected()).count() / scored();
  }
}
