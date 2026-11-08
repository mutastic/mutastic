package io.mutastic.engine;

import java.time.Duration;
import java.util.List;

/// The tests that failed the coverage pass, run again on their own with no mutant in, to tell a
/// flaky one from one that fails every time. A test runs alone; a class that failed as a whole, in
/// its setup or teardown (a `@BeforeAll`, `@AfterAll`, JUnit 4's or TestNG's `@BeforeClass`, a
/// class rule, a static initialiser), runs again with all its tests, as nothing smaller runs its
/// setup.
final class Reruns {
  /// How many times a test that failed the coverage pass is run again on its own before it counts
  /// as failing. One rerun wasn't enough: on spec-tictactoe a test that fails about half the time
  /// under mutastic failed its single rerun too in 1 run of 10, stopping the run.
  static final int TIMES = 3;

  private final Coverage coverage;
  private final TestWorker worker;
  private final Duration wholePass;

  /// @param wholePass how long the coverage pass ran: a class whose setup failed ran none of its
  ///   tests, so nothing says how long it takes, but not longer than every test together
  Reruns(Coverage coverage, TestWorker worker, Duration wholePass) {
    this.coverage = coverage;
    this.worker = worker;
    this.wholePass = wholePass;
  }

  /// The failures a rerun would show: each class that failed as a whole, and each test of no class
  /// among them. A class's tests that failed with it (TestNG says its tests failed when the class's
  /// setup does) failed because it did.
  static List<String> withoutTheirClasses(List<String> failing) {
    var classes = failing.stream().filter(TestIds::isClass).toList();
    return failing.stream()
        .filter(
            test ->
                TestIds.isClass(test)
                    || classes.stream().noneMatch(testClass -> TestIds.inClass(test, testClass)))
        .toList();
  }

  /// Whether `test` passes again on its own within [#TIMES] tries. A failure the worker could only
  /// name by the platform's id (an engine's) can't be selected again, so it fails.
  boolean passesAgain(String test) {
    if (!TestIds.canRunAgain(test)) {
      return false;
    }
    var timeout = Engine.timeoutFor(TestIds.isClass(test) ? wholePass : coverage.timeOf(test));
    for (int attempt = 0; attempt < TIMES; attempt++) {
      if (worker.passes(test, timeout)) {
        return true;
      }
    }
    return false;
  }
}
