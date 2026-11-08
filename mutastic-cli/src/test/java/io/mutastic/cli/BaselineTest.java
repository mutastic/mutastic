package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// What mutastic does when the tests fail before any mutant: a flaky test, or a class whose setup
/// is flaky, is left out and named, and tests that really fail stop the run.
class BaselineTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));

  @TempDir Path work;

  @Test
  void aFlakyTestIsLeftOutAndNamed() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("flaky"), work.resolve("flaky"));

    var run = Samples.mutastic("run", project.toString(), "--format=agent");

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.FAILED);
    assertThat(run.out())
        .contains("flaky: StockTest#aSaleReducesStock failed, then passed when run again");
    // Only the flaky test reaches afterSale(): without it, nothing does, and nothing is caught by
    // it.
    assertThat(run.out())
        .contains(
            "not covered (no test reaches these lines, or only a flaky one does",
            "- src/main/java/com/example/stock/Stock.java:10 afterSale() (2 mutants)");
  }

  /// `samples/flaky-setup`: a class per framework whose setup fails the first time and every third
  /// time after. Run again as a class it passes, so it's flaky: none of its tests catches anything,
  /// though its setup would fail against every third mutant.
  @Test
  void aClassWhoseSetupFailsNowAndThenIsLeftOutAndNamed() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("flaky-setup"), work.resolve("flaky-setup"));

    var run = Samples.mutastic("run", project.toString(), "--format=agent", "--threshold=0");

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.PASSED);
    for (var testClass :
        List.of("JupiterSetupTest", "VintageSetupTest", "TestNgSetupTest", "JupiterTeardownTest")) {
      assertThat(run.out())
          .contains(
              "flaky: %s as a whole (its setup or teardown, outside any test) failed, then passed when run again; left out of this run with every test in it, so their catches don't count."
                  .formatted(testClass));
    }
    // What only the flaky classes reach is reached by nothing, the teardown's too, whose test ran
    // and passed; what the healthy class checks is caught by it.
    assertThat(run.out())
        .contains(
            "- src/main/java/com/example/setup/Prices.java:11 halved() (",
            "- src/main/java/com/example/setup/Prices.java:15 thirded() (",
            "- src/main/java/com/example/setup/Prices.java:19 quartered() (",
            "- src/main/java/com/example/setup/Prices.java:23 fifthed() (")
        .doesNotContain("Prices.java:7");
  }

  @Test
  void aClassWhoseSetupFailsEveryTimeStopsTheRunAndSaysWhere() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("flaky-setup"), work.resolve("flaky-setup"));
    var test = project.resolve("src/test/java/com/example/setup/JupiterSetupTest.java");
    Files.writeString(test, Files.readString(test).replace("runs % 3 == 1", "runs > 0"));

    var run = Samples.mutastic("run", project.toString(), "--format=agent");

    assertThat(run.exit()).as(run.out()).isEqualTo(Main.TESTS_FAIL);
    assertThat(run.err())
        .startsWith(
            "mutastic: the tests fail without any mutant: com.example.setup.JupiterSetupTest (the class as a whole, with IllegalStateException, each time it ran: its setup or teardown fails outside any test, such as a `@BeforeAll`, `@AfterAll`, `@BeforeClass`, `@AfterClass`, a class rule or a static initialiser); their output is in ");
  }

  @Test
  void aTestThatFailsEveryTryStopsTheRun() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("pricing"), work.resolve("pricing"));
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    Files.writeString(
        test, Files.readString(test).replace("assertEquals(25, ", "assertEquals(26, "));

    var run = Samples.mutastic("run", project.toString(), "--format=agent");

    assertThat(run.exit()).isEqualTo(Main.TESTS_FAIL);
    assertThat(run.err())
        .startsWith(
            "mutastic: the tests fail without any mutant: com.example.pricing.PricingTest#bigOrdersGetTenPercentOff");
  }
}
