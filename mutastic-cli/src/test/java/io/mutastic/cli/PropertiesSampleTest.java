package io.mutastic.cli;

import static io.mutastic.cli.Samples.mutastic;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/// `samples/properties`: functions checked only by property tests, QuickTheories' and Kotest's,
/// which mutastic seeds, and junit-quickcheck's, which it can't. A property draws its inputs at
/// random, so mutastic gives every test JVM of a run one seed, runs a mutant's properties without
/// shrinking, names the input that caught each mutant, and says which frameworks it can't seed.
class PropertiesSampleTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));

  /// `isAdult`'s `>=` made `>`: only an age of 18 catches it, and the property draws 12 of 60.
  private static final String AT_18 = "m05acd6c8";

  /// `reversed`'s loop stopping one short: any list but the empty one catches it.
  private static final String ONE_SHORT = "m264953a1";

  /// `clamp`'s `x < low` made always false: any number below 0 catches it.
  private static final String NO_FLOOR = "m9a66e570";

  private static final String NOTE =
      "note: property tests in %s run with their own seeds, which mutastic can't set: a mutant only"
          + " some of their inputs catch can be caught in one run and missed in the next";

  /// A jar named as jqwik's is, holding nothing.
  private static final String JQWIK_STUB = "jqwik-0.0.0-stub.jar";

  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir Path work;

  /// Two runs are one run: every mutant's status is the same, and so is the input each seeded
  /// property names, the first its seed draws that fails, unshrunk. junit-quickcheck's inputs are
  /// its own draw's, and its property here catches whatever it draws. `run-started` says which
  /// frameworks were seeded, with what, and the run ends with a note on the one that wasn't.
  @Test
  void aRunWithTheSameSeedIsTheSameRun() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("properties"), work.resolve("properties"));
    var first = mutastic("run", project.toString(), "--format=jsonl", "--report=none");
    var second = mutastic("run", project.toString(), "--format=jsonl", "--report=none");

    assertThat(said(finished(second.out()))).isEqualTo(said(finished(first.out())));
    assertThat(started(first.out()).get("propertyTests"))
        .isEqualTo(
            JSON.readTree(
                """
                {"frameworks": ["junit-quickcheck", "quicktheories", "kotest"], "seed": "0",
                 "seeded": ["quicktheories", "kotest"], "unseeded": ["junit-quickcheck"]}"""));
    assertThat(first.err().lines()).contains(NOTE.formatted("junit-quickcheck"));
    var mutants = finished(first.out());
    // Seed 0 draws no 18 for the property on ages, in any run.
    assertThat(mutants.get(AT_18).get("status").asString()).isEqualTo("Survived");
    var oneShort = mutants.get(ONE_SHORT);
    assertThat(oneShort.get("caughtBy").asString())
        .isEqualTo("com.example.props.ListsTest#reversesTwice");
    assertThat(oneShort.get("caughtInput").asString())
        .as("the first list that fails, not shrunk")
        .startsWith("[")
        .isNotEqualTo("[0]");
    assertThat(mutants.get(NO_FLOOR).get("caughtInput").asString()).isEqualTo("Arg 0: -13");
    assertThat(mutants.values())
        .filteredOn(mutant -> mutant.at("/location/method").asString().startsWith("twice("))
        .isNotEmpty()
        .allSatisfy(
            mutant ->
                assertThat(mutant.get("caughtBy").asString())
                    .isEqualTo("com.example.props.TwiceTest#doubles(int)"));
  }

  /// Another seed is another draw: the person's seed 2 draws an 18, which catches the boundary seed
  /// 0's draw misses. A mutant run by id has its input shrunk once, for its card: `[0]`, not the
  /// long list the run caught it with. A jar named as jqwik's is detected, and only listed.
  @Test
  void aNamedMutantsInputIsShrunkForItsCard() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("properties"), work.resolve("properties"));
    seededWithAJqwikJar(project, "2");

    var run =
        mutastic(
            "run",
            project.toString(),
            "--format=jsonl",
            "--only=%s,%s,%s".formatted(AT_18, ONE_SHORT, NO_FLOOR));

    var mutants = finished(run.out());
    assertThat(mutants.get(AT_18).get("status").asString()).isEqualTo("Killed");
    assertThat(mutants.get(AT_18).get("caughtInput").asString()).isEqualTo("18");
    assertThat(mutants.get(ONE_SHORT).get("caughtInput").asString()).isEqualTo("[0]");
    assertThat(mutants.get(NO_FLOOR).get("caughtInput").asString()).isEqualTo("Arg 0: -1");
    var properties = started(run.out()).get("propertyTests");
    assertThat(properties.get("seed").asString()).isEqualTo("2");
    assertThat(properties.get("unseeded").toString()).isEqualTo("[\"junit-quickcheck\",\"jqwik\"]");
    assertThat(run.err().lines()).contains(NOTE.formatted("junit-quickcheck and jqwik"));

    var verified = mutastic("verify", ONE_SHORT, project.toString());

    assertThat(verified.exit()).isEqualTo(Main.PASSED);
    assertThat(verified.out() + verified.err())
        .contains(ONE_SHORT + " is caught by com.example.props.ListsTest#reversesTwice with `[0]`");
  }

  /// The person's seed for QuickTheories, as a build sets a test's system property, and a jar named
  /// as jqwik's on the test classpath: an empty one, as jqwik itself is never run here.
  private static void seededWithAJqwikJar(Path project, String seed) throws IOException {
    var libs = Files.createDirectories(project.resolve("libs"));
    try (var jar =
        new JarOutputStream(Files.newOutputStream(libs.resolve(JQWIK_STUB)), new Manifest())) {
      jar.flush();
    }
    var build = project.resolve("build.gradle.kts");
    var launcher = "    testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")";
    var platform = "    useJUnitPlatform()";
    Files.writeString(
        build,
        Files.readString(build)
            .replace(platform, platform + "\n    systemProperty(\"QT_SEED\", \"" + seed + "\")")
            .replace(
                launcher, launcher + "\n    testRuntimeOnly(files(\"libs/" + JQWIK_STUB + "\"))"));
  }

  /// Each mutant's status, and for a seeded property's catch, the input it names.
  private static List<String> said(Map<String, JsonNode> mutants) {
    return mutants.values().stream()
        .map(
            mutant -> {
              boolean seeded = !mutant.path("caughtBy").asString("").contains("TwiceTest");
              var input = seeded ? mutant.path("caughtInput").asString("-") : "-";
              return mutant.get("id").asString()
                  + " "
                  + mutant.get("status").asString()
                  + " "
                  + input;
            })
        .toList();
  }

  private static JsonNode started(String events) {
    return events(events).stream()
        .filter(event -> event.get("reason").asString().equals("run-started"))
        .findFirst()
        .orElseThrow();
  }

  /// Each mutant's event by its id, in the order of the ids: events come as mutants finish.
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
