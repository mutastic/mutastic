package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The output the docs show is what mutastic prints: each file in docs/src/data/output is a
/// command's output on the pricing sample, in colour, compared here with a fresh run. A change to
/// what mutastic prints fails this until the docs' copy is written again.
///
/// To write it: `./gradlew :mutastic-cli:exportDocsOutput`.
class DocsOutputTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final Path OUTPUT = Path.of(System.getProperty("mutastic.docsOutput"));
  private static final boolean WRITE = Boolean.getBoolean("mutastic.docsOutput.write");
  private static final String ID = "m855c8e3c";

  /// What a person at a terminal sees.
  private static final Map<String, String> TERMINAL =
      Map.of("FORCE_COLOR", "1", "MUTASTIC_FORMAT", "full");

  /// What changes from run to run: when it started and how long it took.
  private static final Pattern TIMINGS =
      Pattern.compile("\\d+(\\.\\d+)?(ms|s)\\b|\\d\\d:\\d\\d:\\d\\d");

  @TempDir Path work;

  /// The quickstart's tour, in order: a run that misses one mutant, that mutant explained, the
  /// suggested test proving it caught, and the run after it.
  @Test
  void theQuickstart() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    shown("run.ansi", project, mutastic("run", project.toString(), "--workers=4"));
    shown("explain.ansi", project, mutastic("explain", ID, project.toString()));
    addTheSuggestedTest(project);
    shown("verify.ansi", project, mutastic("verify", ID, project.toString()));
    shown("run-again.ansi", project, mutastic("run", project.toString(), "--workers=4"));
  }

  private static Samples.Result mutastic(String... args) {
    var result = Samples.mutastic(TERMINAL, args);
    assertThat(result.exit()).as(String.join(" ", args)).isEqualTo(Main.PASSED);
    return result;
  }

  /// The test the card suggests, as the quickstart adds it.
  private static void addTheSuggestedTest(Path project) throws Exception {
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    var source = Files.readString(test).stripTrailing();
    var added =
        """

          @Test
          void discountWithTotal100() {
            assertEquals(10, new Pricing().discount(100));
          }
        }
        """;
    Files.writeString(test, source.substring(0, source.lastIndexOf('}')).stripTrailing() + added);
  }

  /// As the docs show it, from the repository's root, where `samples/pricing` is typed. Timings are
  /// kept as they were when it was written, and left out of the comparison.
  private static void shown(String name, Path project, Samples.Result result) throws Exception {
    var output = result.out().replace(project.toString(), "samples/pricing");
    var file = OUTPUT.resolve(name);
    if (WRITE) {
      Files.createDirectories(OUTPUT);
      Files.writeString(file, output, StandardCharsets.UTF_8);
    }
    assertThat(withoutTimings(Files.readString(file, StandardCharsets.UTF_8)))
        .as("docs/src/data/output/%s: ./gradlew :mutastic-cli:exportDocsOutput", name)
        .isEqualTo(withoutTimings(output));
  }

  private static String withoutTimings(String output) {
    return TIMINGS.matcher(output).replaceAll("…");
  }
}
