package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
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
    // The second doesn't: the catch didn't repeat, and with the same code the result changed. None
    // of its catches count: what it reached ran again without it.
    assertThat(second.out())
        .contains(
            "vs last run: 57.1% → 42.9% (same code)",
            "likely flaky: StockTest#restockingWorks; with the code unchanged, whether it catches",
            "likely flaky: StockTest#restockingWorks; its catches of",
            "None of its catches count: 2 mutants it reached ran again without it");
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
            // Only the flaky test reached it: without it, nothing does.
            "\"before\":\"Killed\",\"after\":\"NoCoverage\"",
            "\"likelyFlaky\":[{\"test\":\"com.example.stock.StockTest#restockingWorks\",\"mutants\":[\"m4b6a65ac\",\"mb3b4bcf9\"],"
                + "\"reasons\":[\"changed-since-last-run\",\"catch-not-repeated\"],"
                + "\"rerun\":[\"m4b6a65ac\",\"mb3b4bcf9\"]}]");
    assertThat(second.out())
        .contains("\"unconfirmedCatchBy\":\"com.example.stock.StockTest#restockingWorks\"");
  }

  /// Decision 27: the run that names it counts none of its catches, and remembers it; the next run,
  /// without `--confirm-catches`, confirms each of its catches 3 times, and names it again.
  @Test
  void aLikelyFlakyTestsCatchesDontCountAndTheNextRunsConfirmThem() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("flaky"));

    Samples.mutastic("run", project.toString(), "--format=agent", "--workers=1");
    var naming =
        Samples.mutastic(
            "run", project.toString(), "--format=agent", "--workers=1", "--confirm-catches");
    var watched = Samples.mutastic("run", project.toString(), "--format=agent", "--workers=1");

    assertThat(naming.out())
        .contains(
            "None of its catches count: 2 mutants it reached ran again without it, and the next"
                + " runs confirm each catch it makes 3 times.");
    assertThat(watch(project)).isEqualTo("com.example.stock.StockTest#restockingWorks\t0\n");
    assertThat(watched.out())
        .startsWith("mutastic: FAIL · score 42.9%")
        .contains(
            "likely flaky: StockTest#restockingWorks; its catches of m4b6a65ac, mb3b4bcf9 didn't repeat");
    assertThat(watch(project)).isEqualTo("com.example.stock.StockTest#restockingWorks\t0\n");
  }

  /// A mutant only a likely flaky test reached says so, in `verify` and in a run's list: it isn't a
  /// mutant no test reaches, and a person shouldn't go and write the test that already exists.
  @Test
  void aMutantOnlyALikelyFlakyTestReachedSaysSo() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("flaky"));
    watching(project, "com.example.stock.StockTest#restockingWorks\t0\n");

    var verify = Samples.mutastic("verify", "m4b6a65ac", project.toString(), "--workers=1");
    var agent =
        Samples.mutastic(
            "run", project.toString(), "--only=m4b6a65ac", "--format=agent", "--workers=1");
    var human =
        Samples.mutastic(
            "run", project.toString(), "--only=m4b6a65ac", "--format=concise", "--workers=1");
    var run = Samples.mutastic("run", project.toString(), "--format=agent", "--workers=1");

    var only =
        "only StockTest#restockingWorks reached it, and it's likely flaky: its catch didn't count";
    assertThat(verify.out()).contains("m4b6a65ac isn't covered: " + only);
    assertThat(agent.out())
        .contains("m4b6a65ac isn't covered: " + only)
        .doesNotContain("isn't reached");
    assertThat(human.out() + human.err()).contains("m4b6a65ac: " + only);
    assertThat(run.out()).contains("or only a flaky one does").contains("  " + only);
  }

  /// A watched test whose every catch repeats has held a run; at the third, it's forgotten.
  @Test
  void aWatchedTestWhoseCatchesHoldIsForgottenAtItsThirdRun() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("flaky"));
    watching(project, "com.example.stock.StockTest#reordersAtTheReorderLevel\t2\n");

    var run = Samples.mutastic("run", project.toString(), "--format=agent", "--workers=1");

    assertThat(run.out())
        .contains(
            "watched: StockTest#reordersAtTheReorderLevel, likely flaky in an earlier run: each"
                + " catch it made had to repeat 3 times; 3 of 3 runs held, so it's no longer"
                + " watched");
    assertThat(project.resolve("build/mutastic/last-runs/flaky-watch.tsv")).doesNotExist();
  }

  /// Shares may run side by side on one work directory, and each sees only part of a test's
  /// catches: a share confirms against the watch, and leaves it as it was.
  @Test
  void aShareConfirmsAgainstTheWatchAndNeverWritesIt() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("flaky"));
    var kept =
        """
        com.example.stock.StockTest#reordersAtTheReorderLevel\t2
        com.example.stock.StockTest#restockingWorks\t1
        """;
    watching(project, kept);

    for (var share : List.of("--shard=1/2", "--shard=2/2")) {
      Samples.mutastic("run", project.toString(), "--format=agent", "--workers=1", share);
    }

    assertThat(watch(project)).isEqualTo(kept);
  }

  private static void watching(Path project, String lines) throws java.io.IOException {
    var file = project.resolve("build/mutastic/last-runs/flaky-watch.tsv");
    java.nio.file.Files.createDirectories(file.getParent());
    java.nio.file.Files.writeString(file, lines);
  }

  private static String watch(Path project) throws java.io.IOException {
    return java.nio.file.Files.readString(
        project.resolve("build/mutastic/last-runs/flaky-watch.tsv"));
  }
}
