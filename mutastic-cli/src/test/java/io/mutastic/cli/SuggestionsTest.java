package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Suggested tests on `samples/suggestions`: AssertJ-style tests, a static method, `long`
/// arguments, and a random method that must get none.
class SuggestionsTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "suggestions");

  @TempDir
  Path work;

  @Test
  void suggestionsPastedInCatchTheirMutantsAndRandomnessGetsNone() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("suggestions"));

    var run = Samples.mutastic("run", project.toString(), "--format=jsonl");
    var suggested = new LinkedHashMap<String, String>(); // mutant id → test code
    run.out().lines().filter(line -> line.contains("\"status\":\"Survived\"")).forEach(line -> {
      if (line.contains("\"suggestedTest\"")) {
        suggested.put(line.replaceAll(".*\"id\":\"(m[0-9a-f]{8})\".*", "$1"),
            line.replaceAll(".*\"code\":\"((?:[^\"\\\\]|\\\\.)*)\".*", "$1").replace("\\n", "\n").replace("\\\"", "\""));
      } else {
        assertThat(line).as("only the random method goes without").contains("\"method\":\"jitter(I)I\"");
      }
    });

    assertThat(suggested.values()).containsExactlyInAnyOrder("""
        @Test
        void lateFeeWithDaysLate7() {
          assertThat(Rates.lateFee(7L)).isEqualTo(0L); // today 0L · with this mutant 5L
        }""", """
        @Test
        void inCreditWithBalance0() {
          assertThat(Rates.inCredit(0)).isFalse(); // today false · with this mutant true
        }""");

    var test = project.resolve("src/test/java/com/example/rates/RatesTest.java");
    var code = String.join("\n", suggested.values().stream().map(method -> method.indent(2)).toList());
    Files.writeString(test, Files.readString(test).replaceFirst("\\}\\s*$", "\n" + code + "}\n"));
    for (Map.Entry<String, String> entry : suggested.entrySet()) {
      var verified = Samples.mutastic("verify", entry.getKey(), project.toString());
      assertThat(verified.exit()).as(entry.getValue() + "\n" + verified.out() + verified.err()).isEqualTo(Main.PASSED);
    }
  }
}
