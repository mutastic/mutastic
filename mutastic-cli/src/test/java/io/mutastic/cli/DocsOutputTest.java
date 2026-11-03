package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
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
  private static final Path FLAKY = Path.of(System.getProperty("mutastic.samples"), "flaky");

  /// Colour and style codes, to find a line by its words.
  private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");

  /// What a person at a terminal sees.
  private static final Map<String, String> TERMINAL =
      Map.of("FORCE_COLOR", "1", "MUTASTIC_FORMAT", "full");

  /// What changes from run to run: when it started and how long it took, in words or in JSON.
  private static final Pattern TIMINGS =
      Pattern.compile("\\d+(\\.\\d+)?(ms|s)\\b|\\d\\d:\\d\\d:\\d\\d|Ms\":\\d+");

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

  /// The pricing sample in each format but `full` (the quickstart's run is that), each from a fresh
  /// copy, with one worker so mutants finish in the same order every time.
  @Test
  void eachFormat() throws Exception {
    for (String format : List.of("brief", "concise", "grouped", "agent", "jsonl")) {
      var project = Samples.copy(SAMPLE, work.resolve(format).resolve("pricing"));
      var result =
          Samples.mutastic(
              Map.of("FORCE_COLOR", "1"),
              "run",
              project.toString(),
              "--format=" + format,
              "--workers=1");
      assertThat(result.exit()).as(format).isEqualTo(Main.PASSED);
      shown("format-" + format + ".ansi", project, result);
    }
  }

  /// The flaky sample, run twice: the second time, every check has something to say. Only the end
  /// of its output is shown, from the first warning on.
  @Test
  void theFlakySample() throws Exception {
    var project = Samples.copy(FLAKY, work.resolve("flaky"));
    var run = List.of("run", project.toString(), "--workers=1");

    Samples.mutastic(TERMINAL, run.toArray(String[]::new));
    var second = new ArrayList<>(run);
    second.add("--confirm-catches");
    var result = Samples.mutastic(TERMINAL, second.toArray(String[]::new));

    assertThat(result.exit()).isEqualTo(Main.FAILED);
    shown("flaky.ansi", project, fromTheFirstWarning(result));
  }

  private static Samples.Result fromTheFirstWarning(Samples.Result result) {
    var lines = result.out().lines().toList();
    int first = 0;
    while (!ANSI.matcher(lines.get(first)).replaceAll("").contains("! flaky:")) {
      first++;
    }
    var shown = String.join("\n", lines.subList(first, lines.size())) + "\n";
    return new Samples.Result(result.exit(), shown, result.err());
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

  /// As the docs show it, from the repository's root, where the sample is typed as
  /// `samples/<name>`. Timings are kept as they were when it was written, and left out of the
  /// comparison; so a file is written again only when more than its timings changed.
  private static void shown(String name, Path project, Samples.Result result) throws Exception {
    var output = result.out().replace(project.toString(), "samples/" + project.getFileName());
    var file = OUTPUT.resolve(name);
    var written = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    if (WRITE && !withoutTimings(written).equals(withoutTimings(output))) {
      Files.createDirectories(OUTPUT);
      Files.writeString(file, output, StandardCharsets.UTF_8);
      written = output;
    }
    assertThat(withoutTimings(written))
        .as("docs/src/data/output/%s: ./gradlew :mutastic-cli:exportDocsOutput", name)
        .isEqualTo(withoutTimings(output));
  }

  private static String withoutTimings(String output) {
    return TIMINGS.matcher(output).replaceAll("…");
  }
}
