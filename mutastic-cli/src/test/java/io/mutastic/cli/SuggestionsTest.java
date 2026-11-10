package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Suggested tests on `samples/suggestions`: AssertJ-style tests, a static method, `long`
/// arguments, a record made with its own constructor, and a random method that must get none.
class SuggestionsTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "suggestions");

  @TempDir Path work;

  /// A skipped mutant has nothing to catch: verify says why it's skipped, and passes, rather than
  /// send anyone looking for the test that should reach it.
  @Test
  void verifyingASkippedMutantSaysWhyAndPasses() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("suggestions"));

    var run = Samples.mutastic("run", project.toString(), "--format=jsonl");
    var skipped =
        run.out()
            .lines()
            .filter(line -> line.contains("\"status\":\"Ignored\""))
            .findFirst()
            .orElseThrow();
    var skippedId = skipped.replaceAll(".*\"id\":\"(m[0-9a-f]{8})\".*", "$1");
    var reason = skipped.replaceAll(".*\"statusReason\":\"([^\"]+)\".*", "$1");
    var skip = Samples.mutastic("verify", skippedId, project.toString());

    assertThat(skip.exit()).isEqualTo(Main.PASSED);
    assertThat(skip.out())
        .contains(skippedId + " is skipped (" + reason + "), so it isn't run and needs no test");
  }

  @Test
  void suggestionsPastedInCatchTheirMutantsAndRandomnessGetsNone() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("suggestions"));

    var run = Samples.mutastic("run", project.toString(), "--format=jsonl");
    var suggested = new LinkedHashMap<String, String>(); // mutant id → test code
    run.out()
        .lines()
        .filter(line -> line.contains("\"status\":\"Survived\""))
        .forEach(
            line -> {
              if (line.contains("\"suggestedTest\"")) {
                suggested.put(
                    line.replaceAll(".*\"id\":\"(m[0-9a-f]{8})\".*", "$1"),
                    line.replaceAll(".*\"code\":\"((?:[^\"\\\\]|\\\\.)*)\".*", "$1")
                        .replace("\\n", "\n")
                        .replace("\\\"", "\""));
              } else {
                // Suggestions pass numbers, never null, so `start == null` can't get one.
                assertThat(line)
                    .as("only the random method and the null check go without")
                    .containsAnyOf(
                        "\"method\":\"jitter(I)I\"", "\"text\":\"IFNULL -> never jump\"");
              }
            });

    // Two mutants on a side of one condition share a test: one of each.
    assertThat(java.util.Set.copyOf(suggested.values()))
        .containsExactlyInAnyOrder(
            """
        @Test
        void lateFeeWithDaysLate7() {
          assertThat(Rates.lateFee(7L)).isEqualTo(0L); // today 0L · with this mutant 5L
        }""",
            """
        @Test
        void inCreditWithBalance0() {
          assertThat(Rates.inCredit(0)).isFalse(); // today false · with this mutant true
        }""",
            """
        @Test
        void openOnWithDay0ForWindow0And0() {
          assertThat(new Window(0, 0L).openOn(0)).isFalse(); // today false · with this mutant true
        }""",
            """
        @Test
        void openOnWithDay0ForWindow5And5() {
          assertThat(new Window(5, 5L).openOn(0)).isFalse(); // today false · with this mutant true
        }""",
            """
        @Test
        void openOnWithDay5ForWindow5And5() {
          assertThat(new Window(5, 5L).openOn(5)).isFalse(); // today false · with this mutant true
        }""",
            """
        @Test
        void openOnWithDay5ForWindow5And10() {
          assertThat(new Window(5, 10L).openOn(5)).isTrue(); // today true · with this mutant false
        }""");

    // Each into the test class it was written for: RatesTest, or WindowTest for the record.
    for (var code : java.util.Set.copyOf(suggested.values())) {
      var testClass = code.contains("new Window(") ? "WindowTest" : "RatesTest";
      var test = project.resolve("src/test/java/com/example/rates/" + testClass + ".java");
      Files.writeString(
          test, Files.readString(test).replaceFirst("\\}\\s*$", "\n" + code.indent(2) + "}\n"));
    }
    for (Map.Entry<String, String> entry : suggested.entrySet()) {
      var verified = Samples.mutastic("verify", entry.getKey(), project.toString());
      assertThat(verified.exit())
          .as(entry.getValue() + "\n" + verified.out() + verified.err())
          .isEqualTo(Main.PASSED);
    }
  }
}
