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
/// @param flakyEvidence for each flaky test, what it failed with and what it reaches that changes
///   from run to run
/// @param cases the test cases run, each row of a parameterized test one; -1 when not counted
/// @param skippedCases the tests and cases skipped: turned off, or aborted by an assumption; -1
///   when not counted
/// @param separateJvms a note for each test class that runs one of the project's `main` classes in
///   a JVM of its own, which neither mutants nor coverage reach
@Internal
public record CoveragePass(
    int jdk,
    int tests,
    Duration took,
    int mutants,
    List<String> flaky,
    List<FlakyTest> flakyEvidence,
    int cases,
    int skippedCases,
    List<String> separateJvms) {
  public CoveragePass {
    flaky = List.copyOf(flaky);
    flakyEvidence = List.copyOf(flakyEvidence);
    separateJvms = List.copyOf(separateJvms);
  }

  /// A pass that found no test running the project in a JVM of its own.
  public CoveragePass(
      int jdk,
      int tests,
      Duration took,
      int mutants,
      List<String> flaky,
      List<FlakyTest> flakyEvidence,
      int cases,
      int skippedCases) {
    this(jdk, tests, took, mutants, flaky, flakyEvidence, cases, skippedCases, List.of());
  }

  /// This pass, with the cases it counted.
  public CoveragePass withCases(int ran, int skipped) {
    return new CoveragePass(
        jdk, tests, took, mutants, flaky, flakyEvidence, ran, skipped, separateJvms);
  }

  /// This pass, with a note for each test class that runs the project in a JVM of its own.
  public CoveragePass withSeparateJvms(List<String> notes) {
    return new CoveragePass(
        jdk, tests, took, mutants, flaky, flakyEvidence, cases, skippedCases, notes);
  }

  /// A pass whose cases weren't counted.
  public CoveragePass(
      int jdk,
      int tests,
      Duration took,
      int mutants,
      List<String> flaky,
      List<FlakyTest> flakyEvidence) {
    this(jdk, tests, took, mutants, flaky, flakyEvidence, -1, -1);
  }

  /// A pass with nothing known of why its flaky tests are flaky.
  public CoveragePass(int jdk, int tests, Duration took, int mutants, List<String> flaky) {
    this(jdk, tests, took, mutants, flaky, List.of());
  }
}
