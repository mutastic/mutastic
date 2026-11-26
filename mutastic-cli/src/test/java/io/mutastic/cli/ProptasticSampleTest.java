package io.mutastic.cli;

import static io.mutastic.cli.Samples.mutastic;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/// `samples/proptastic`: functions checked only by proptastic's properties. proptastic isn't
/// published yet, so the sample's tests run against its fixture, `src/surface`, which reproduces
/// the surface mutastic depends on: the entry point's class, the system properties, a seed only as
/// `0x` and hex digits, a stream keyed on the test's signature, and the failure report, stacked
/// when a value is wide. With `-Pproptastic=<coordinates>` the same runs go against the library.
class ProptasticSampleTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));

  /// `reversed`'s `i < j` made always true: it swaps past the middle and off the end, so every list
  /// fails, the empty one too.
  private static final String EMPTY = "m7b90ad2d";

  /// `sorted`'s loop never running: every list but the empty one fails, and the first drawn is
  /// wide.
  private static final String AT_THE_END = "md6505d64";

  /// `isAdult`'s `age >= 18` made always false: every age from 18 fails.
  private static final String FROM_18 = "mf5e3b605";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path work;

  /// Two runs are one run, with proptastic found by its class in a jar named for no one, seeded
  /// from `0x0` (the fixture refuses a bare number, so nothing would pass), and its first
  /// counterexample read, unshrunk, from the stacked form when it's wide. No run writes the
  /// person's files: no events, no saved failure, no corpus.
  @Test
  void aRunWithTheSameSeedIsTheSameRun() throws IOException {
    var project = sample(null);

    var first = mutastic("run", project.toString(), "--format=jsonl", "--report=none");
    var second = mutastic("run", project.toString(), "--format=jsonl", "--report=none");

    assertThat(said(finished(second.out()))).isEqualTo(said(finished(first.out())));
    assertThat(started(first.out()).get("propertyTests"))
        .isEqualTo(
            JSON.readTree(
                """
                {"frameworks": ["proptastic"], "seed": "0", "seeded": ["proptastic"]}"""));
    assertThat(first.err()).doesNotContain("note: property tests");
    var wide = finished(first.out()).get(AT_THE_END).get("caughtInput").asString();
    assertThat(wide).as("the first list that failed, stacked").startsWith("a = [");
    assertThat(wide.split(",")).as(wide).hasSizeGreaterThan(5);
    assertThat(project.resolve("build/proptastic")).doesNotExist();
    assertThat(project.resolve(".proptastic")).doesNotExist();
  }

  /// A mutant run by id has its input shrunk once, for its card; the person's seed, `0x` and hex
  /// digits, is the run's, said in decimal.
  @Test
  void aNamedMutantsInputIsShrunkForItsCard() throws IOException {
    var project = sample(null);
    var build = project.resolve("build.gradle.kts");
    Files.writeString(
        build,
        Files.readString(build)
            .replace(
                "    useJUnitPlatform()",
                "    useJUnitPlatform()\n    systemProperty(\"proptastic.seed\", \"0x2a\")"));

    var run =
        mutastic(
            "run",
            project.toString(),
            "--format=jsonl",
            "--report=none",
            "--only=%s,%s,%s".formatted(EMPTY, AT_THE_END, FROM_18));

    var mutants = finished(run.out());
    assertThat(mutants.get(EMPTY).get("caughtInput").asString()).isEqualTo("a = []");
    assertThat(mutants.get(AT_THE_END).get("caughtInput").asString()).isEqualTo("a = [0]");
    assertThat(mutants.get(FROM_18).get("caughtInput").asString()).isEqualTo("a = 18");
    assertThat(started(run.out()).at("/propertyTests/seed").asString()).isEqualTo("42");

    var verified = mutastic("verify", AT_THE_END, project.toString());

    assertThat(verified.out() + verified.err())
        .contains(
            AT_THE_END
                + " is caught by com.example.proptastic.ListsTest#sortsAsTheJdkDoes with `a = [0]`");
  }

  /// The same runs against proptastic itself, once it can be had: `-Pproptastic=` its coordinates.
  /// It names its draws as the lambda does, `xs = [0]`, where the fixture says `a`.
  @Test
  @EnabledIfSystemProperty(named = "mutastic.proptastic", matches = ".+")
  void theLibraryItselfIsSeededAndReadTheSame() throws IOException {
    var project = sample(System.getProperty("mutastic.proptastic"));

    var first = mutastic("run", project.toString(), "--format=jsonl", "--report=none");
    var second = mutastic("run", project.toString(), "--format=jsonl", "--report=none");
    var named =
        mutastic(
            "run",
            project.toString(),
            "--format=jsonl",
            "--report=none",
            "--only=%s,%s,%s".formatted(EMPTY, AT_THE_END, FROM_18));

    assertThat(said(finished(second.out()))).isEqualTo(said(finished(first.out())));
    assertThat(started(first.out()).at("/propertyTests/seeded").toString())
        .isEqualTo("[\"proptastic\"]");
    var mutants = finished(named.out());
    assertThat(value(mutants.get(EMPTY))).isEqualTo("[]");
    assertThat(value(mutants.get(AT_THE_END))).isEqualTo("[0]");
    assertThat(value(mutants.get(FROM_18))).isEqualTo("18");
    assertThat(project.resolve("build/proptastic")).doesNotExist();
    assertThat(project.resolve(".proptastic")).doesNotExist();
  }

  /// A copy of the sample, against the library at `coordinates` when they're given.
  private Path sample(@Nullable String coordinates) throws IOException {
    var project = Samples.copy(SAMPLES.resolve("proptastic"), work.resolve("proptastic"));
    if (coordinates != null) {
      Files.writeString(project.resolve("gradle.properties"), "proptastic=" + coordinates + "\n");
    }
    return project;
  }

  /// The caught input without its draw's name: `[0]` for `xs = [0]`.
  private static String value(JsonNode mutant) {
    return mutant.get("caughtInput").asString().replaceFirst("^\\w+ = ", "");
  }

  /// Each mutant's status and the input that caught it.
  private static List<String> said(Map<String, JsonNode> mutants) {
    return mutants.values().stream()
        .map(
            mutant ->
                mutant.get("id").asString()
                    + " "
                    + mutant.get("status").asString()
                    + " "
                    + mutant.path("caughtInput").asString("-"))
        .toList();
  }

  private static JsonNode started(String events) {
    return events(events).stream()
        .filter(event -> event.get("reason").asString().equals("run-started"))
        .findFirst()
        .orElseThrow();
  }

  /// Each mutant's event by its id.
  private static Map<String, JsonNode> finished(String events) {
    var finished = new TreeMap<String, JsonNode>();
    events(events).stream()
        .filter(event -> event.get("reason").asString().equals("mutant-finished"))
        .forEach(event -> finished.put(event.get("id").asString(), event));
    return finished;
  }

  private static List<JsonNode> events(String events) {
    return events.lines().map(JSON::readTree).toList();
  }
}
