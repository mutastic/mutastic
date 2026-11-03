package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `samples/testng`: TestNG tests, and no JUnit at all. mutastic brings TestNG's engine for the
/// JUnit Platform, leaves out the group the build leaves out, and writes its suggested test the
/// TestNG way, which, pasted in, catches the mutant.
class TestNgTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "testng");

  @TempDir Path work;

  @Test
  void testNgTestsRunAndTheSuggestionIsTestNgs() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("testng"));

    var run = Samples.mutastic("run", project.toString(), "--format=agent");

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.PASSED);
    // The `slow` group would catch it; the build leaves it out, so mutastic does too.
    assertThat(run.out())
        .contains("1 missed")
        .contains("no test checks `lateFee()` at `days == 30`")
        .contains("covered by: RatesTest#aLongWayLateHasAFee")
        .contains("public void lateFeeWithDays30() {")
        .contains("assertEquals(new Rates().lateFee(30), 25);");
    var id = run.out().replaceAll("(?s).*1\\. missed (m[0-9a-f]{8}).*", "$1");

    var test = project.resolve("src/test/java/com/example/rates/RatesTest.java");
    Files.writeString(
        test,
        Files.readString(test)
            .replaceFirst(
                "\\}\\s*$",
                """

                  @Test
                  public void lateFeeWithDays30() {
                    assertEquals(new Rates().lateFee(30), 25);
                  }
                }
                """));
    var verified = Samples.mutastic("verify", id, project.toString());

    assertThat(verified.out())
        .as(verified.err())
        .contains("is caught by com.example.rates.RatesTest#lateFeeWithDays30");
  }
}
