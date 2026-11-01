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

  /// Detected mutants as a share of all of them, from 0 to 100; 100 when there are none.
  public double score() {
    return results.isEmpty() ? 100 : 100.0 * results.stream().filter(result -> result.status().detected()).count() / results.size();
  }
}
