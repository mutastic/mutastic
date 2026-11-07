package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A flaky test that passes the coverage pass: `StockTest#restockingWorks` checks nothing and fails
/// every second time it runs in a JVM, so it "catches" a mutant by luck. One worker, so which runs
/// it fails is the same every time.
class FlakyRunsTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "flaky");

  @TempDir Path work;

  @Test
  void confirmingCatchesAndComparingRunsBothNameIt() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("flaky"));

    var first = Samples.mutastic("run", project.toString(), "--format=agent", "--workers=1");
    var second =
        Samples.mutastic(
            "run", project.toString(), "--format=agent", "--workers=1", "--confirm-catches");

    // The first run takes its lucky catch at face value; there's nothing to compare with yet.
    assertThat(first.out()).doesNotContain("vs last run", "likely flaky");
    // The second doesn't: the catch didn't repeat, and with the same code the result changed.
    assertThat(second.out())
        .contains(
            "vs last run: 77.8% → 55.6% (same code)",
            "likely flaky: StockTest#restockingWorks; with the code unchanged, whether it catches",
            "likely flaky: StockTest#restockingWorks; its catches of",
            "(StockTest#restockingWorks failed once, then passed: flaky)");
  }

  @Test
  void theEventStreamCarriesTheSameEvidence() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("flaky"));

    Samples.mutastic("run", project.toString(), "--format=agent", "--workers=1");
    var second =
        Samples.mutastic(
            "run", project.toString(), "--format=jsonl", "--workers=1", "--confirm-catches");

    var finished =
        second
            .out()
            .lines()
            .filter(line -> line.contains("\"reason\":\"run-finished\""))
            .findFirst()
            .orElseThrow();
    assertThat(finished)
        .contains(
            "\"sameCodeAsLastRun\":true",
            "\"before\":\"Killed\",\"after\":\"Survived\"",
            "\"likelyFlaky\":[{\"test\":\"com.example.stock.StockTest#restockingWorks\",\"mutants\":[\"m4b6a65ac\",\"mb3b4bcf9\"],"
                + "\"reasons\":[\"changed-since-last-run\",\"catch-not-repeated\"]}]");
    assertThat(second.out())
        .contains("\"unconfirmedCatchBy\":\"com.example.stock.StockTest#restockingWorks\"");
  }
}
