package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Four identical lines in one method (`samples/identical`, `i = i + 1;` four times, with a test
/// too weak to catch any of them): javac compiles them to the same instructions, so what tells
/// their mutants apart is their place among the method's sites, never their line. Each still has
/// its own id, its own card on its own line, and `verify` acts on that line alone.
class IdenticalLinesTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "identical");
  private static final Path STEPS = Path.of("src/main/java/com/example/steps/Steps.java");
  private static final String LINE = "    i = i + 1;\n";

  /// `+` becoming `-` on each of the four lines, by the line it's on.
  private static final Map<Integer, String> MINUS =
      Map.of(6, "mab60505e", 7, "m7b623724", 8, "m0103e572", 9, "mfd0f452e");

  @TempDir Path work;

  @Test
  void eachLineHasItsOwnIdAndCard() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("identical"));

    var jsonl = Samples.mutastic("run", project.toString(), "--format=jsonl", "--report=none");
    var agent = Samples.mutastic("run", project.toString(), "--format=agent", "--report=none");
    var full = run(project, "--format=full", "--color=never");

    MINUS.forEach(
        (line, id) -> {
          assertThat(jsonl.out()).containsPattern(event(id, line));
          assertThat(agent.out())
              .contains("missed %s: tests pass even when".formatted(id))
              .containsPattern(id + ":.*\n\\s+at " + Pattern.quote(STEPS + ":" + line) + "\n");
          assertThat(full.out())
              .containsPattern(" · " + id + "\n\\s+┌─ " + Pattern.quote(STEPS + ":" + line) + "\n");
        });
    assertThat(agent.out().split("\n\\d+\\. missed ", -1)).hasSize(5);
    assertThat(full.out().split("\\[✗ MISSED]", -1)).hasSize(5);
  }

  /// `verify` runs only its own mutant, on its own line, as its progress shows.
  @Test
  void verifyActsOnItsOwnLine() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("identical"));

    var verified = Samples.mutastic("verify", MINUS.get(7), project.toString(), "--color=never");

    assertThat(verified.exit()).as(verified.err()).isEqualTo(Main.FAILED);
    assertThat(verified.out()).isEqualTo("mutastic: " + MINUS.get(7) + " is still missed\n");
    assertThat(verified.err())
        .contains("(1 mutant | 1 missed)")
        .containsPattern("fourMore\\(\\) :7 \\+ becomes -")
        .doesNotContainPattern("fourMore\\(\\) :[689] ");
  }

  /// Lines added above move the cards, not the ids.
  @Test
  void linesAboveMoveNoId() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("identical"));
    edit(project, "    int i = start;\n", "    // counting up\n\n    int i = start;\n");

    var run = run(project, "--format=jsonl");

    MINUS.forEach((line, id) -> assertThat(run.out()).containsPattern(event(id, line + 2)));
  }

  /// The limit: an id is a place among the method's sites, so deleting the second line hands its
  /// place to the third, the third's to the fourth, and the fourth's id is gone.
  @Test
  void deletingALineHandsItsIdToTheNext() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("identical"));
    edit(project, LINE + LINE + LINE + LINE, LINE + LINE + LINE);

    var run = run(project, "--format=jsonl");

    assertThat(run.out())
        .containsPattern(event(MINUS.get(6), 6))
        .containsPattern(event(MINUS.get(7), 7))
        .containsPattern(event(MINUS.get(8), 8))
        .doesNotContain(MINUS.get(9));
  }

  private static Samples.Result run(Path project, String... options) {
    var args = new java.util.ArrayList<>(java.util.List.of("run", project.toString()));
    args.add("--report=none");
    args.addAll(java.util.List.of(options));
    return Samples.mutastic(args.toArray(String[]::new));
  }

  /// A mutant's JSON Lines event with its id and line.
  private static Pattern event(String id, int line) {
    return Pattern.compile("\"id\":\"" + id + "\"[^\n]*\"line\":" + line + ",");
  }

  private static void edit(Path project, String from, String to) throws Exception {
    var file = project.resolve(STEPS);
    var text = Files.readString(file);
    assertThat(text).contains(from);
    Files.writeString(file, text.replace(from, to));
  }
}
