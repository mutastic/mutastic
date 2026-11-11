package io.mutastic.cli;

import static io.mutastic.cli.Samples.mutastic;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/// Every card mutastic gives on the samples, kept in src/test/cards: one for each missed mutant, as
/// an agent reads it and as a person does, and `explain` for each missed mutant of two samples.
/// People and agents both act on these words, so a change to any of them is a diff to read, not a
/// surprise: this fails until the copy is written again.
///
/// To write it: `./gradlew :mutastic-cli:exportCards`.
class CardsTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));
  private static final Path CARDS = Path.of(System.getProperty("mutastic.cards"));
  private static final boolean WRITE = Boolean.getBoolean("mutastic.cards.write");

  /// The samples whose mutants explain too: the quickstart's, and the one that suggests tests.
  private static final List<String> EXPLAINED = List.of("pricing", "suggestions");

  /// What changes from run to run: how long it took, in words or in JSON.
  private static final Pattern TIMINGS =
      Pattern.compile("\\d+(\\.\\d+)?(ms|s)\\b|\\d\\d:\\d\\d:\\d\\d|Ms\":\\d+");

  /// How many workers ran, and how many were replaced: how the machine ran it, not what the cards
  /// say, and it differs from one machine to the next.
  private static final Pattern WORKERS =
      Pattern.compile("\\d+ workers?( \\(\\d+ replaced after a timeout\\))?");

  private static final Pattern MISSED = Pattern.compile("(?m)^\\d+\\. missed (?<id>m[0-9a-f]{8})");

  @TempDir Path work;

  @ParameterizedTest
  @ValueSource(
      strings = {
        "pricing",
        "operators",
        "suggestions",
        "loading",
        "nested",
        "observable",
        "selection",
        "testng",
        "lombok",
        "constants"
      })
  void everyCardOnTheSample(String sample) throws Exception {
    var project = Samples.copy(SAMPLES.resolve(sample), work.resolve(sample));
    var agent = run(project, "--format=agent");
    var full = run(project, "--format=full");
    assertThat(PlusLines.describedInBrackets(agent.out() + full.out()))
        .as("+ lines that describe, not show")
        .isEmpty();
    kept(sample, "agent.txt", project, agent.out(), said(agent));
    kept(sample, "full.txt", project, full.out(), said(full));
    if (EXPLAINED.contains(sample)) {
      var explained = new StringBuilder();
      var errors = new StringBuilder();
      var missed = MISSED.matcher(agent.out());
      while (missed.find()) {
        var id = missed.group("id");
        var explain =
            mutastic("explain", id, project.toString(), "--color=never", "--progress=none");
        explained.append("$ mutastic explain ").append(id).append('\n').append(explain.out());
        explained.append('\n');
        errors.append(said(explain));
      }
      kept(sample, "explain.txt", project, explained.toString(), errors.toString());
    }
  }

  /// What a run said besides its output: its exit code and anything on stderr. A run that fails
  /// prints nothing on stdout, so a card that comes out empty is shown with why.
  private static String said(Samples.Result run) {
    return "%nexit %d%s"
        .formatted(run.exit(), run.err().isEmpty() ? "" : ", stderr:\n" + run.err());
  }

  /// The run's output, a card for every missed mutant.
  private static Samples.Result run(Path project, String format) {
    return mutastic(
        "run",
        project.toString(),
        format,
        "--each-mutant",
        "--workers=4",
        "--color=never",
        "--hyperlinks=never",
        "--progress=none",
        "--report=none");
  }

  /// As run from the repository's root, timings left out: written when `WRITE`, else compared.
  private static void kept(String sample, String name, Path project, String output, String said)
      throws Exception {
    var text =
        WORKERS
            .matcher(
                TIMINGS
                    .matcher(output.replace(project.toString(), "samples/" + sample))
                    .replaceAll("…"))
            .replaceAll("… workers");
    var file = CARDS.resolve(sample).resolve(name);
    if (WRITE) {
      Files.createDirectories(file.getParent());
      Files.writeString(file, text, StandardCharsets.UTF_8);
    }
    var written = Files.exists(file) ? Files.readString(file, StandardCharsets.UTF_8) : "";
    assertThat(text)
        .as("src/test/cards/%s/%s: ./gradlew :mutastic-cli:exportCards%s", sample, name, said)
        .isEqualTo(written);
  }
}
