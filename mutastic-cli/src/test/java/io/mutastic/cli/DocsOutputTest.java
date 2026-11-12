package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.json.JsonMapper;

/// The output the docs show is what mutastic prints: each file in docs/src/data/output is a
/// command's output on a sample, in colour, compared here with a fresh run. A change to what
/// mutastic prints fails this until the docs' copy is written again. And the code the pages quote
/// is the samples' own: a block marked `{/* from: path */}` is that file's text, word for word, and
/// the test the quickstart adds is the one its tour adds.
///
/// To write it: `./gradlew :mutastic-cli:exportDocsOutput`.
class DocsOutputTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final Path OUTPUT = Path.of(System.getProperty("mutastic.docsOutput"));
  private static final boolean WRITE = Boolean.getBoolean("mutastic.docsOutput.write");
  private static final String ID = "m855c8e3c";
  private static final Path FLAKY = Path.of(System.getProperty("mutastic.samples"), "flaky");
  private static final Path SUGGESTIONS =
      Path.of(System.getProperty("mutastic.samples"), "suggestions");
  private static final Path CONFIGURED =
      Path.of(System.getProperty("mutastic.samples"), "configured");
  private static final Path PAGES = Path.of(System.getProperty("mutastic.docs"));
  private static final Path STOCK = Path.of(System.getProperty("mutastic.samples"), "stock");

  /// The stock sample's `left > 0` becoming `left != 0` where `left` is `@NonNegative`, skipped,
  /// and where it's `@PositiveOrZero`, which runs.
  private static final String CLAIMED = "m46ea303d";

  private static final String VALIDATED = "mb1c584d6";

  /// A mutant of the configured sample's generated code, which its rule skips.
  private static final String GENERATED = "m6393711d";

  /// A marked code block in a page: `{/* from: path */}` quotes a file; `{/* added to: path */}` is
  /// code the page adds to one.
  private static final Pattern MARKED =
      Pattern.compile(
          "(?m)^\\{/\\* (?<kind>from|added to|changed in): (?<path>\\S+) \\*/}\\n```\\w+\\n(?<code>(?s:.*?))^```$");

  /// Colour and style codes, to find a line by its words.
  private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");

  /// What a person at a terminal sees.
  private static final Map<String, String> TERMINAL =
      Map.of("FORCE_COLOR", "1", "MUTASTIC_FORMAT", "full");

  /// What changes from run to run: when it started and how long it took, in words or in JSON, and
  /// how many covering tests ran before a mutant's result was known, as they run fastest first and
  /// a test's time moves with the machine's load: in JSON, and in the summary's `Test runs` row.
  private static final Pattern TIMINGS =
      Pattern.compile(
          "\\d+(\\.\\d+)?(ms|s)\\b|\\d\\d:\\d\\d:\\d\\d|Ms\" ?: ?\\d+|\"testsRun\" ?: ?\\d+"
              + "|Test runs(?:\u001B\\[[0-9;]*m)*  \\d+");

  @TempDir Path work;

  /// The quickstart's tour, in order: a run that misses one mutant, that mutant explained, the
  /// suggested test proving it caught, and the run after it.
  @Test
  void theQuickstart() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    shown("run.ansi", project, mutastic("run", project.toString(), "--workers=4"));
    shown("explain.ansi", project, mutastic("explain", ID, project.toString()));
    var events = mutastic("explain", ID, project.toString(), "--format=jsonl");
    shown("explained-event.json", project, theExplainedEvent(events));
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
      if (format.equals("jsonl")) {
        shown("event-missed.json", project, theMissedEvent(result));
        shown("mutation-json.json", project, theReport(project.resolve("build/mutastic")));
      }
    }
  }

  /// The run's `mutation.json`, as Report files shows it: its one missed mutant, with the file's
  /// text, its absolute root and its timings left out, as they're long or change from machine to
  /// machine.
  private static Samples.Result theReport(Path work) throws IOException {
    var mapper = JsonMapper.builder().build();
    var report = mapper.readTree(work.resolve("mutation.json").toFile());
    var files = report.path("files");
    var path = files.propertyNames().iterator().next();
    var file = files.path(path);
    var missed =
        (tools.jackson.databind.node.ObjectNode)
            file.path("mutants").values().stream()
                .filter(mutant -> mutant.path("status").asString("").equals("Survived"))
                .findFirst()
                .orElseThrow()
                .deepCopy();
    missed.remove("duration");
    var trimmed = mapper.createObjectNode();
    trimmed.set("$schema", report.path("$schema"));
    trimmed.set("schemaVersion", report.path("schemaVersion"));
    trimmed.set("thresholds", report.path("thresholds"));
    var entry = trimmed.putObject("files").putObject(path);
    entry.set("language", file.path("language"));
    entry.put("source", "…");
    entry.putArray("mutants").add(missed);
    trimmed.set("framework", report.path("framework"));
    var laidOut = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(trimmed) + "\n";
    return new Samples.Result(0, laidOut, "");
  }

  /// The one missed mutant's event, laid out to be read, as Schemas and stability shows it. The
  /// event for mutant `id`, laid out as the docs show it.
  private static Samples.Result theEvent(Samples.Result result, String id) {
    var mapper = JsonMapper.builder().build();
    var event =
        result
            .out()
            .lines()
            .map(mapper::readTree)
            .filter(line -> line.path("id").asString("").equals(id))
            .findFirst()
            .orElseThrow();
    var laidOut = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(event) + "\n";
    return new Samples.Result(result.exit(), laidOut, result.err());
  }

  /// `explain --format=jsonl`'s own event, the last, laid out as output.mdx shows it.
  private static Samples.Result theExplainedEvent(Samples.Result result) {
    var mapper = JsonMapper.builder().build();
    var explained = mapper.readTree(result.out().lines().toList().getLast());
    var laidOut = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(explained) + "\n";
    return new Samples.Result(result.exit(), laidOut, result.err());
  }

  private static Samples.Result theMissedEvent(Samples.Result result) {
    var mapper = JsonMapper.builder().build();
    var missed =
        result
            .out()
            .lines()
            .map(mapper::readTree)
            .filter(event -> event.path("status").asString("").equals("Survived"))
            .findFirst()
            .orElseThrow();
    var laidOut = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(missed) + "\n";
    return new Samples.Result(result.exit(), laidOut, result.err());
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

  /// The suggestions sample's `inCredit()` card: two mutants on one line, one card. Only that card
  /// is shown.
  @Test
  void aSharedCard() throws Exception {
    var project = Samples.copy(SUGGESTIONS, work.resolve("suggestions"));

    var result =
        Samples.mutastic(TERMINAL, "run", project.toString(), "--workers=1", "--report=none");

    assertThat(result.exit()).isEqualTo(Main.FAILED);
    shown("shared-card.ansi", project, theCard(result, "no test checks inCredit()"));
  }

  /// The test-first guide's cycle, on the pricing sample in a repository of its own, from the
  /// guide's own code: its failing test and stub stop a run, the code that passes it leaves the
  /// cycle's edge untested, the card's tests make `verify` pass, and the cycle's run passes after.
  @Test
  void aTestFirstCycle() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    git(project, "init", "-q");
    git(project, "add", "-A");
    git(project, "commit", "-q", "-m", "start");
    var steps = marked(PAGES.resolve("guides/test-first.mdx"));
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    var code = project.resolve("src/main/java/com/example/pricing/Pricing.java");
    assertThat(steps)
        .extracting(Marked::kind, Marked::path)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "added to", "samples/pricing/" + project.relativize(test)),
            org.assertj.core.groups.Tuple.tuple(
                "added to", "samples/pricing/" + project.relativize(code)),
            org.assertj.core.groups.Tuple.tuple(
                "changed in", "samples/pricing/" + project.relativize(code)),
            org.assertj.core.groups.Tuple.tuple(
                "added to", "samples/pricing/" + project.relativize(test)));

    added(test, steps.get(0));
    added(code, steps.get(1));
    var red = Samples.mutastic(TERMINAL, "run", project.toString());
    assertThat(red.exit()).as(red.err()).isEqualTo(Main.TESTS_FAIL);
    shown("test-first-red.ansi", project, itsMessage(red));

    var stub = steps.get(1).code().indent(2).stripTrailing();
    var source = Files.readString(code);
    assertThat(source).contains(stub);
    Files.writeString(code, source.replace(stub, steps.get(2).code().indent(2).stripTrailing()));
    var run = Samples.mutastic(TERMINAL, "run", project.toString(), "--since=HEAD", "--workers=1");
    assertThat(run.exit()).as(run.err()).isEqualTo(Main.FAILED);
    shown("test-first-run.ansi", project, run);

    added(test, steps.get(3));
    shown(
        "test-first-verify.ansi",
        project,
        mutastic("verify", "m809c9c9b,md0b32a4f", project.toString()));
    var again =
        Samples.mutastic(TERMINAL, "run", project.toString(), "--since=HEAD", "--workers=1");
    assertThat(again.exit()).as(again.err()).isEqualTo(Main.PASSED);
    shown("test-first-again.ansi", project, again);
  }

  /// `block`'s code, put at the end of the class in `file`, as a person adds it.
  private static void added(Path file, Marked block) throws IOException {
    var source = Files.readString(file).stripTrailing();
    Files.writeString(
        file,
        source.substring(0, source.lastIndexOf('}')).stripTrailing()
            + "\n\n"
            + block.code().indent(2)
            + "}\n");
  }

  private static void git(Path project, String... arguments) throws Exception {
    var command =
        new ArrayList<>(
            List.of("git", "-c", "user.name=docs", "-c", "user.email=docs@example.com"));
    command.addAll(List.of(arguments));
    var exit =
        new ProcessBuilder(command).directory(project.toFile()).inheritIO().start().waitFor();
    assertThat(exit).as(String.join(" ", arguments)).isZero();
  }

  /// The configuration guide's example: the configured sample without its mutastic.yaml, where the
  /// generated code counts as not covered, then with it, where its rule skips that code, and one of
  /// those skipped mutants explained.
  @Test
  void aRuleThatSkipsGeneratedCode() throws Exception {
    var project = Samples.copy(CONFIGURED, work.resolve("configured"));
    var settings = Files.readString(project.resolve("mutastic.yaml"));
    Files.delete(project.resolve("mutastic.yaml"));
    var without = concise(project);
    Files.writeString(project.resolve("mutastic.yaml"), settings);
    var with = concise(project);

    assertThat(without.exit()).isEqualTo(Main.FAILED);
    shown("configured-without.ansi", project, without);
    shown("configured.ansi", project, with);
    shown("configured-explain.ansi", project, mutastic("explain", GENERATED, project.toString()));
  }

  /// The Equivalent mutants page's pair: a comparison's change that differs only below zero,
  /// skipped where the Checker Framework says the value is never negative, and run where only
  /// Jakarta Validation does; and the skipped one's event, with its reason and `skipKind`.
  @Test
  void aValueTheCodeSaysIsNeverNegative() throws Exception {
    var project = Samples.copy(STOCK, work.resolve("stock"));

    shown("claimed-explain.ansi", project, mutastic("explain", CLAIMED, project.toString()));
    var validated = Samples.mutastic(TERMINAL, "explain", VALIDATED, project.toString());

    assertThat(validated.out()).contains("left > 0");
    shown("validated-explain.ansi", project, validated);

    var events =
        mutastic("run", project.toString(), "--format=jsonl", "--workers=1", "--only=" + CLAIMED);
    var skipped = theEvent(events, CLAIMED);
    assertThat(skipped.out()).contains("\"skipKind\" : \"equivalent\"", "@NonNegative");
    shown("claimed-event.json", project, skipped);
  }

  /// Troubleshooting's examples, one for each exit status a person meets: a score below the
  /// threshold, a mutant's id given to another project, and a test that fails with no mutant in.
  @Test
  void eachExitStatus() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    var below =
        Samples.mutastic(
            TERMINAL,
            "run",
            project.toString(),
            "--format=concise",
            "--threshold=95",
            "--workers=4");
    var elsewhere = Samples.mutastic(TERMINAL, "verify", GENERATED, project.toString());
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    Files.writeString(test, Files.readString(test).replace("assertEquals(25,", "assertEquals(24,"));
    var failing = Samples.mutastic(TERMINAL, "run", project.toString());

    assertThat(below.exit()).isEqualTo(Main.FAILED);
    assertThat(elsewhere.exit()).isEqualTo(Main.USAGE);
    assertThat(failing.exit()).isEqualTo(Main.TESTS_FAIL);
    shown("below-threshold.ansi", project, below);
    shown("id-elsewhere.ansi", project, itsMessage(elsewhere));
    shown("tests-fail.ansi", project, itsMessage(failing));
  }

  /// Troubleshooting's example of tests that pass and reach nothing, which measure nothing: the
  /// pricing sample, with its tests replaced by one that never calls its code.
  @Test
  void testsThatReachNothing() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    Files.delete(project.resolve("src/test/java/com/example/pricing/PricingTest.java"));
    Samples.writeTestReachingNothing(project);

    var nothing = Samples.mutastic(TERMINAL, "run", project.toString());

    assertThat(nothing.exit()).isEqualTo(Main.USAGE);
    shown("nothing-covered.ansi", project, itsMessage(nothing));
  }

  private static Samples.Result concise(Path project) {
    return Samples.mutastic(TERMINAL, "run", project.toString(), "--format=concise", "--workers=1");
  }

  /// What mutastic says on stderr, without its progress, whose first line names its version.
  private static Samples.Result itsMessage(Samples.Result result) {
    var said = result.err().lines().filter(line -> line.startsWith("mutastic: ")).toList();
    assertThat(said).as(result.err()).isNotEmpty();
    return new Samples.Result(result.exit(), String.join("\n", said) + "\n", "");
  }

  /// The card whose headline starts `headline`, up to the next card or file.
  private static Samples.Result theCard(Samples.Result result, String headline) {
    var lines = result.out().lines().toList();
    int first = 0;
    while (!ANSI.matcher(lines.get(first)).replaceAll("").contains(headline)) {
      first++;
    }
    int end = first + 1;
    while (end < lines.size()
        && !ANSI.matcher(lines.get(end)).replaceAll("").strip().matches("\\[?✗ MISSED.*|❯ .*")) {
      end++;
    }
    var shown = String.join("\n", lines.subList(first, end)).stripTrailing() + "\n";
    return new Samples.Result(result.exit(), shown, result.err());
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

  /// The test the card suggests, as the quickstart adds it: the page's own block.
  private static void addTheSuggestedTest(Path project) throws Exception {
    var block =
        marked(PAGES.resolve("getting-started/quickstart.mdx")).stream()
            .filter(found -> found.kind().equals("added to"))
            .findFirst()
            .orElseThrow();
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    assertThat(block.path()).isEqualTo("samples/pricing/" + project.relativize(test));
    var source = Files.readString(test).stripTrailing();
    var indented = block.code().indent(2);
    Files.writeString(
        test,
        source.substring(0, source.lastIndexOf('}')).stripTrailing() + "\n\n" + indented + "}\n");
  }

  /// Every block a page quotes from a file outside the operator pages, which DocsOperatorsTest
  /// checks, is that file's text, word for word.
  @Test
  void everyQuoteIsTheFilesText() throws Exception {
    var root = SAMPLE.getParent().getParent();
    var quoted = 0;
    for (Path page : pagesOutsideTheOperators()) {
      for (Marked block : marked(page)) {
        if (block.kind().equals("from")) {
          assertThat(Files.readString(root.resolve(block.path())))
              .as("%s, quoted in %s", block.path(), PAGES.relativize(page))
              .contains(block.code());
          quoted++;
        }
      }
    }
    assertThat(quoted).as("blocks quoted from a file").isPositive();
  }

  private record Marked(String kind, String path, String code) {}

  private static List<Marked> marked(Path page) throws IOException {
    var found = new ArrayList<Marked>();
    var matcher = MARKED.matcher(Files.readString(page));
    while (matcher.find()) {
      found.add(new Marked(matcher.group("kind"), matcher.group("path"), matcher.group("code")));
    }
    return found;
  }

  private static List<Path> pagesOutsideTheOperators() throws IOException {
    var operators = PAGES.resolve("concepts/operators");
    try (Stream<Path> files = Files.walk(PAGES)) {
      return files
          .filter(file -> file.toString().endsWith(".mdx") && !file.startsWith(operators))
          .sorted()
          .toList();
    }
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
