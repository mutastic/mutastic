package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/// `--at`, run as a person runs it on the pricing sample: `mutants` lists the mutants on a line,
/// `run` runs them and says each one's fate, lines with none are refused with the nearest that have
/// one, and a file that isn't one of the module's sources is refused, naming them.
class AtLinesTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));

  private static final String PRICING = "src/main/java/com/example/pricing/Pricing.java";

  /// An id at the start of a line `mutants` prints.
  private static final Pattern LISTED = Pattern.compile("(?m)^(m[0-9a-f]{8}) ");

  private static final Pattern LINE = Pattern.compile("\"line\":(\\d+)");

  @TempDir static Path work;

  private static Path project;

  @BeforeAll
  static void aCopyOfThePricingSample() throws Exception {
    project = Samples.copy(SAMPLES.resolve("pricing"), work.resolve("pricing"));
  }

  @Test
  void mutantsListsTheMutantsOnALine() {
    var listed = Samples.mutastic("mutants", project.toString(), "--at=" + PRICING + ":17");

    assertThat(listed.exit()).isZero();
    assertThat(ids(listed.out())).containsExactly("m855c8e3c", "m53e1dc80", "mfb551430");
    assertThat(listed.out()).endsWith("3 mutants\n");
  }

  /// A path is absolute, from where mutastic runs, or from the project.
  @Test
  void anAbsolutePathNamesTheSameFile() {
    var file = project.resolve(PRICING).toAbsolutePath();
    var listed =
        Samples.mutastic(
            "mutants", project.toString(), "--at=%s:18,%s:25-26".formatted(file, file));

    assertThat(ids(listed.out()))
        .containsExactly(
            "mabd5e44c", "mf7bb3882", "m2de3396d", "m73f3386e", "m091729b1", "m39e3e756");
  }

  @Test
  void aRunSaysEachFateAndCountsThem() {
    var run =
        Samples.mutastic(
            Map.of("MUTASTIC_FORMAT", "full"),
            "run",
            project.toString(),
            "--at=" + PRICING + ":17-20",
            "--report=json");

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.FAILED);
    assertThat(run.out())
        .contains(" lines 17–20 of Pricing.java: 6 mutants, 5 caught, 1 missed\n")
        .contains("m855c8e3c is still missed")
        .contains("m3264f639 is caught by")
        .doesNotContain("Score ");
    assertThat(run.err()).endsWith(Main.reportsLeftOutWith("--at") + "\n");
    assertThat(project.resolve("build/mutastic/mutation.json")).doesNotExist();
  }

  @Test
  void theEventsNameTheLinesAndTheRunPassesWhenEachIsCaught() {
    var run =
        Samples.mutastic("run", project.toString(), "--at=" + PRICING + ":25", "--format=jsonl");
    var events = run.out().lines().map(JsonMapper.shared()::readTree).toList();

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.PASSED);
    assertThat(at(event(events, "run-started"))).containsExactly(PRICING + ":25");
    var finished = event(events, "run-finished");
    assertThat(at(finished)).containsExactly(PRICING + ":25");
    assertThat(finished.get("passed").asBoolean()).isTrue();
    assertThat(finished.has("only")).isFalse();
    assertThat(LINE.matcher(run.out()).results().map(found -> found.group(1)).distinct())
        .containsExactly("25");
  }

  @Test
  void theAgentFormatGivesEachFate() {
    var run =
        Samples.mutastic("run", project.toString(), "--at=" + PRICING + ":25", "--format=agent");

    assertThat(run.out())
        .contains("m2de3396d is caught by")
        .contains("m73f3386e is caught by")
        .contains("m091729b1 is caught by");
  }

  /// No match is an error, never a silent zero: the nearest lines with a mutant are named, with the
  /// command that lists them, which parses.
  @Test
  void aLineWithNoMutantIsRefusedWithTheNearestThatHaveOne() {
    var run =
        Samples.mutastic(
            Map.of("MUTASTIC_FORMAT", "jsonl"),
            "run",
            project.toString(),
            "--at=" + PRICING + ":16");

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    var failed = failed(run);
    assertThat(failed.get("code").asString()).isEqualTo("no-mutant-at");
    assertThat(failed.get("message").asString())
        .isEqualTo(
            "no mutant on Pricing.java:16; there are on 17, 18, 20, 25 and 26. To see them:");
    var offered = run.err().lines().filter(line -> line.startsWith("  mutastic ")).findFirst();
    assertThat(offered).hasValueSatisfying(line -> assertThat(line).contains(PRICING + ":17-26"));
    var words = offered.orElseThrow().strip().split(" ");
    assertThat(Main.Command.parse(List.of(words).subList(1, words.length).toArray(String[]::new)))
        .satisfies(command -> assertThat(command.subcommand()).isEqualTo(Main.Subcommand.MUTANTS));
  }

  @Test
  void aTestIsntOneOfTheSources() {
    var run =
        Samples.mutastic(
            Map.of("MUTASTIC_FORMAT", "jsonl"),
            "mutants",
            project.toString(),
            "--at=src/test/java/com/example/pricing/PricingTest.java:10");

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .contains(
            "mutastic: src/test/java/com/example/pricing/PricingTest.java isn't in the module's"
                + " sources: --at takes a line of the code mutastic mutates, in src/main/java\n");
  }

  @Test
  void aFileThatIsntThereIsRefused() {
    var run =
        Samples.mutastic(
            "mutants", project.toString(), "--at=src/main/java/com/example/pricing/Nope.java:3");

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .contains(
            "mutastic: there's no file src/main/java/com/example/pricing/Nope.java, from here or"
                + " from the project: --at takes a path from either\n");
  }

  /// With `--since`, only the changed lines' mutants of those asked for run; asked for lines the
  /// change didn't touch, there's none to run, and that's said.
  @Test
  void withSinceOnlyTheChangedLinesRun() throws Exception {
    var changed = Samples.copy(SAMPLES.resolve("pricing"), work.resolve("changed"));
    git(changed, "init", "-q");
    git(changed, "add", ".");
    git(changed, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "start");
    var pricing = changed.resolve(PRICING);
    Files.writeString(
        pricing,
        Files.readString(pricing).replace("return total / 10;", "return total / 10; // a tenth"));

    var run =
        Samples.mutastic(
            "run",
            changed.toString(),
            "--since=HEAD",
            "--at=" + PRICING + ":17-18",
            "--format=jsonl");
    var elsewhere =
        Samples.mutastic(
            Map.of("MUTASTIC_FORMAT", "jsonl"),
            "run",
            changed.toString(),
            "--since=HEAD",
            "--at=" + PRICING + ":25");

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.PASSED);
    assertThat(LINE.matcher(run.out()).results().map(found -> found.group(1)).distinct())
        .containsExactly("18");
    assertThat(failed(elsewhere).get("code").asString()).isEqualTo("no-mutant-at");
    assertThat(failed(elsewhere).get("message").asString())
        .isEqualTo("no mutant on Pricing.java:25; there's one on 18. To see it:");
  }

  /// From the top of a build of several modules, the file picks its module.
  @Test
  void aModulesFilePicksItsModule() throws Exception {
    var modules = Samples.copy(SAMPLES.resolve("modules"), work.resolve("modules"));

    var listed =
        Samples.mutastic(
            "mutants",
            modules.toString(),
            "--at=app/src/main/java/com/example/app/Checkout.java:9");

    assertThat(listed.exit()).as(listed.err()).isZero();
    assertThat(ids(listed.out())).containsExactly("m9584932c", "m9f77afb0");
  }

  /// Code kotlinc copies in from an inline function is on the lines of the file it was copied from,
  /// as its SMAP maps them and its card shows them: `bump`'s line in Helpers.kt has its copy in
  /// `usesBump`, in Inlines.kt, as well as its own.
  @Test
  void kotlinsInlinedCodeIsOnTheLinesItWasCopiedFrom() throws Exception {
    var shapes = Samples.copy(SAMPLES.resolve("kotlin-shapes"), work.resolve("kotlin-shapes"));

    var listed =
        Samples.mutastic(
            "mutants", shapes.toString(), "--at=src/main/kotlin/com/example/shapes/Helpers.kt:4");

    assertThat(listed.exit()).as(listed.err()).isZero();
    var lines = listed.out().lines().filter(line -> line.startsWith("m")).toList();
    assertThat(lines)
        .isNotEmpty()
        .allSatisfy(
            line -> assertThat(line).contains(" src/main/kotlin/com/example/shapes/Helpers.kt:4:"));
    assertThat(lines).anySatisfy(line -> assertThat(line).contains("usesBump"));
  }

  private static List<String> ids(String listed) {
    return LISTED.matcher(listed).results().map(found -> found.group(1)).toList();
  }

  private static JsonNode event(List<JsonNode> events, String reason) {
    return events.stream()
        .filter(event -> event.get("reason").asString().equals(reason))
        .findFirst()
        .orElseThrow();
  }

  private static List<String> at(JsonNode event) {
    return EditionContract.strings(event.get("at"));
  }

  private static JsonNode failed(Samples.Result run) {
    return run.out()
        .lines()
        .filter(line -> line.startsWith("{"))
        .map(JsonMapper.shared()::readTree)
        .filter(event -> event.get("reason").asString().equals("run-failed"))
        .reduce((first, last) -> last)
        .orElseThrow(() -> new AssertionError("no run-failed event: " + run.err()));
  }

  private static void git(Path directory, String... args) throws Exception {
    var command = new java.util.ArrayList<>(List.of("git"));
    command.addAll(List.of(args));
    var process = new ProcessBuilder(command).directory(directory.toFile()).inheritIO().start();
    assertThat(process.waitFor()).isZero();
  }
}
