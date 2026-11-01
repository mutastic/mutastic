package io.mutastic.api;

import java.time.Duration;
import java.util.List;

/// What the coverage pass found, before any mutant runs.
///
/// @param jdk the feature version the tests run on
/// @param tests how many tests ran
/// @param took how long the pass took
/// @param mutants how many will run (or be reported as skipped or not covered)
/// @param flaky tests left out because they failed and then passed
public record CoveragePass(int jdk, int tests, Duration took, int mutants, List<String> flaky) {
  public CoveragePass {
    flaky = List.copyOf(flaky);
  }
}
