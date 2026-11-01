package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// What mutastic does when the tests fail before any mutant: a flaky test is left out and
/// named, and tests that really fail stop the run.
class BaselineTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));

  @TempDir
  Path work;

  @Test
  void aFlakyTestIsLeftOutAndNamed() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("flaky"), work.resolve("flaky"));

    var run = Samples.mutastic("run", project.toString(), "--format=agent");

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.FAILED);
    assertThat(run.out()).contains("flaky: StockTest#aSaleReducesStock failed, then passed when run again");
    // Only the flaky test reaches afterSale(): without it, nothing does, and nothing is caught by it.
    assertThat(run.out()).contains("not covered (no test reaches these lines, or only a flaky one does;",
        "- src/main/java/com/example/stock/Stock.java:10 afterSale() (2 mutants)");
  }

  @Test
  void aTestThatFailsTwiceStopsTheRun() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("pricing"), work.resolve("pricing"));
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    Files.writeString(test, Files.readString(test).replace("assertEquals(25, ", "assertEquals(26, "));

    var run = Samples.mutastic("run", project.toString(), "--format=agent");

    assertThat(run.exit()).isEqualTo(Main.TESTS_FAIL);
    assertThat(run.err()).startsWith(
        "mutastic: the tests fail without any mutant: com.example.pricing.PricingTest#bigOrdersGetTenPercentOff");
  }
}
