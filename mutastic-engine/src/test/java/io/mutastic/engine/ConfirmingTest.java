package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.api.Status;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

/// A catch confirmed by running its test again against the mutant: what a run that didn't repeat
/// it, and one JUnit couldn't discover the test for, make of it. The runs are faked, as the worker
/// would answer them.
class ConfirmingTest {
  private static final String TEST = "com.example.StockTest#restockingWorks()";
  private static final String UNDISCOVERED =
      "org.junit.platform.launcher.core.DiscoveryIssueException";
  private static final Confirming WATCHING =
      new Confirming(false, new FlakyHistory(Map.of(), Set.of(TEST)));

  @Test
  void aCatchThatRepeatsEveryTimeCounts() {
    var again =
        answers(
            caught("AssertionFailedError"),
            caught("AssertionFailedError"),
            caught("AssertionFailedError"));
    assertThat(WATCHING.repeats(caught("AssertionFailedError"), again)).isTrue();
  }

  @Test
  void aCatchThatPassesOnceDoesntRepeat() {
    var again = answers(caught("AssertionFailedError"), new TestWorker.Run(Status.MISSED, 1));
    assertThat(WATCHING.repeats(caught("AssertionFailedError"), again)).isFalse();
  }

  /// As #230 found: a copy's selector that JUnit couldn't discover, reported as the test failing.
  /// Taken as a catch that didn't repeat, it named a sound test likely flaky.
  @Test
  void aConfirmationJUnitCouldntDiscoverIsMutasticsOwnError() {
    var again = answers(caught("AssertionFailedError"), caught(UNDISCOVERED));
    assertThatThrownBy(() -> WATCHING.repeats(caught("AssertionFailedError"), again))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TEST)
        .hasMessageContaining("couldn't discover");
  }

  @Test
  void aCatchJUnitCouldntDiscoverIsntConfirmedButThrown() {
    var again = answers(new TestWorker.Run(Status.MISSED, 1));
    assertThatThrownBy(() -> WATCHING.repeats(caught(UNDISCOVERED), again))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining(TEST);
  }

  @Test
  void aCatchNotConfirmedIsntRunAgain() {
    var none = new Confirming(false, FlakyHistory.NONE);
    Supplier<TestWorker.Run> again =
        () -> {
          throw new AssertionError("ran again");
        };
    assertThat(none.repeats(caught("AssertionFailedError"), again)).isTrue();
  }

  private static TestWorker.Run caught(String failure) {
    return new TestWorker.Run(
        Status.CAUGHT, Optional.of(TEST), Optional.of(failure), Optional.of("assertion"), 1, false);
  }

  private static Supplier<TestWorker.Run> answers(TestWorker.Run... runs) {
    var left = new ArrayDeque<>(List.of(runs));
    return left::removeFirst;
  }
}
