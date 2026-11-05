package io.mutastic.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class FlakyTestTest {
  @Test
  void aFailureFromTheNetworkOrADatabaseIsFromOutsideTheCode() {
    var timeout =
        new FlakyTest(
            "a.QuoteTest#b",
            List.of("java.io.UncheckedIOException", "java.net.http.HttpTimeoutException"),
            List.of(),
            List.of());
    assertThat(timeout.failedOutside()).isTrue();
    assertThat(timeout.failedWith()).hasValue("UncheckedIOException");
    assertThat(timeout.outsideCause()).hasValue("HttpTimeoutException");

    assertThat(
            new FlakyTest(
                    "a.B#c",
                    List.of("java.sql.SQLTransientConnectionException"),
                    List.of(),
                    List.of())
                .failedOutside())
        .isTrue();
    assertThat(
            new FlakyTest(
                    "a.B#c", List.of("java.util.concurrent.TimeoutException"), List.of(), List.of())
                .failedOutside())
        .isTrue();

    var assertion =
        new FlakyTest(
            "a.B#c", List.of("org.opentest4j.AssertionFailedError"), List.of(), List.of());
    assertThat(assertion.failedOutside()).isFalse();
    assertThat(assertion.outsideCause()).isEmpty();
    assertThat(new FlakyTest("a.B#c", List.of(), List.of(), List.of()).failedWith()).isEmpty();
  }

  @Test
  void aReportSaysWhatItKnowsOfEachFlakyTest() {
    var evidence = new FlakyTest("a.B#c", List.of(), List.of("new Random()"), List.of());
    var report =
        new Report(
            "p",
            17,
            List.of(),
            1,
            List.of("a.B#c"),
            java.time.Duration.ZERO,
            1,
            1,
            java.time.Duration.ZERO,
            List.of(),
            java.util.Optional.empty(),
            List.of(),
            List.of(evidence));
    assertThat(report.whyFlaky("a.B#c")).hasValue(evidence);
    assertThat(report.whyFlaky("a.B#d")).isEmpty();
    assertThat(report.merge(report).flakyEvidence()).hasSize(2);
  }
}
