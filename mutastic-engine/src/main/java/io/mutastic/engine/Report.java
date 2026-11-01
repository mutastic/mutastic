package io.mutastic.engine;

import java.time.Duration;
import java.util.List;

/// A finished run: every mutant's result, in the order they were found.
///
/// @param jdk the feature version the tests ran on
public record Report(String project, int jdk, List<MutantResult> results, int tests, Duration took) {

  public Report {
    results = List.copyOf(results);
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
