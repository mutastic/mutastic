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
  private static final Path CHECKOUT = Path.of(System.getProperty("mutastic.samples"), "checkout");
  private static final Path FASTPATH = Path.of(System.getProperty("mutastic.samples"), "fastpath");
  private static final String CHECKOUT_TEST =
      "src/test/java/com/example/checkout/CheckoutTest.java";

  /// What a CSV row's cells are split at, and the tests in a block of more than one.
  private static final Pattern COMMA = Pattern.compile(",");

  private static final Pattern BLANK_LINE = Pattern.compile("\n\n");

  /// The stock sample's `left > 0` becoming `left != 0` where `left` is `@NonNegative`, skipped,
  /// and where it's `@PositiveOrZero`, which runs.
  private static final String CLAIMED = "m46ea303d";

  private static final String VALIDATED = "mb1c584d6";

  /// The fastpath sample's check that picks the safe way past 9 digits: `>` becoming `>=`, and the
  /// check made always true. Either sends some number down the safe way, which reads it alike.
  private static final String TO_THE_SAFE_WAY_AT_9 = "mb0e29482";

  private static final String ALWAYS_THE_SAFE_WAY = "m061d8040";

  /// The Equivalent mutants page's reason for ignoring [#TO_THE_SAFE_WAY_AT_9], as it shows it.
  private static final String SAME_AT_9 =
      "Differs only when the fast way and the safe way read 9 digits differently. That can't"
          + " happen because any 9 digits fit in an int, so both read them exactly.";

  /// A mutant of the configured sample's generated code, which its rule skips.
  private static final String GENERATED = "m6393711d";

  /// A mutant of the configured sample's `Customer.toString()`, which mutastic skips as arid.
  private static final String ARID = "mb7fe9c69";

  /// A marked code block in a page: `{/* from: path */}` quotes a file; `{/* added to: path */}` is
  /// code the page adds to one.
  private static final Pattern MARKED =
      Pattern.compile(
          "(?m)^\\{/\\* (?<kind>from|added to|changed in): (?<path>\\S+) \\*/}\\n```\\w+\\n(?<code>(?s:.*?))^```$");

  /// A card's last line but two, `done when mutastic verify <ids> <dir> passes`.
  private static final Pattern DONE_WHEN =
      Pattern.compile("done when\\s+mutastic verify (?<ids>\\S+)");

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

  /// The commit a gate's task starts from, which changes with each repository a test makes.
  private static final Pattern COMMITS = Pattern.compile("(from |--since=)[0-9a-f]{7,40}\\b");

  /// The fence around the project's text in an agent's: each run draws its own token.
  private static final Pattern FENCE = Pattern.compile("(?<marker>mutastic-data )[0-9a-f]{8}>>");

  @TempDir Path work;

  /// The sample's mutants, listed; then the quickstart's tour, in order: a run that misses one
  /// mutant, that mutant explained, the suggested test proving it caught, and the run after it.
  @Test
  void theQuickstart() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    shown("mutants.ansi", project, mutastic("mutants", project.toString()));
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
    // When it ran, and on which JDK, change from run to run.
    var run = (tools.jackson.databind.node.ObjectNode) report.path("mutastic").deepCopy();
    run.put("startedAt", "…");
    ((tools.jackson.databind.node.ObjectNode) run.path("toolchain")).put("version", "…");
    trimmed.set("mutastic", run);
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

  /// What a long run says while it works, on a courier's round: its one test takes a second, and
  /// one mutant makes the courier wait at a door for three seconds more, past what the test took
  /// but inside the mutant's time limit. With one worker each mutant takes its turn, Postage's
  /// first, then Signature's: `needed is always false`, then the slow one. At a terminal, the
  /// screen as that mutant runs, marked SLOW; in a log, from the first file's line on, the
  /// heartbeat that comes while it runs, three and a quarter seconds after Postage's line, with a
  /// second or more to spare either side.
  @Test
  void aLongRun() throws Exception {
    var project = courier(Samples.copy(SAMPLE, work.resolve("courier")));
    var run = new String[] {"run", project.toString(), "--workers=1", "--report=none"};

    var live = Samples.mutastic(TERMINAL, withProgress(run, "bar"));
    assertThat(live.exit()).as(live.err()).isEqualTo(Main.FAILED);
    shown("run-live.ansi", project, new Samples.Result(0, screenAtSlow(live.err()), ""));

    var heartbeat = new java.util.HashMap<>(TERMINAL);
    heartbeat.put("MUTASTIC_HEARTBEAT", COURIER_HEARTBEAT);
    var log = Samples.mutastic(heartbeat, withProgress(run, "counter"));
    assertThat(log.exit()).as(log.err()).isEqualTo(Main.FAILED);
    var lines = log.err().lines().toList();
    int first = 0;
    while (first < lines.size()
        && !ANSI.matcher(lines.get(first)).replaceAll("").startsWith(" ❯")) {
      first++;
    }
    var from = String.join("\n", lines.subList(first, lines.size())) + "\n";
    assertThat(ANSI.matcher(from).replaceAll("").lines())
        .as(log.err())
        .anyMatch(line -> line.startsWith("mutants   ["));
    shown("run-log.ansi", project, new Samples.Result(0, from, ""));
  }

  /// A mutant that makes a loop never end, as Results shows it: `verify` says a timeout caught it.
  /// The counter is a `long`, so the loop that counts up instead runs past any time limit, where an
  /// `int` would wrap round to below 0 and end it.
  @Test
  void aTimeout() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("rounds"));
    try (Stream<Path> files = Files.walk(project.resolve("src"))) {
      for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(file);
      }
    }
    var main = Files.createDirectories(project.resolve("src/main/java/com/example/rounds"));
    var test = Files.createDirectories(project.resolve("src/test/java/com/example/rounds"));
    Files.writeString(
        main.resolve("Rounds.java"),
        """
        package com.example.rounds;

        public final class Rounds {
          private Rounds() {}

          /// How many trips a van that takes `load` parcels makes to deliver `parcels`.
          public static int trips(long parcels, long load) {
            int trips = 0;
            for (long left = parcels; left > 0; left -= load) {
              trips++;
            }
            return trips;
          }
        }
        """);
    Files.writeString(
        test.resolve("RoundsTest.java"),
        """
        package com.example.rounds;

        import static org.junit.jupiter.api.Assertions.assertEquals;

        import org.junit.jupiter.api.Test;

        class RoundsTest {
          @Test
          void threeTripsForTwentyFive() {
            assertEquals(3, Rounds.trips(25, 10));
          }
        }
        """);
    var mutants = Samples.mutastic("mutants", project.toString());
    var id =
        ANSI.matcher(mutants.out())
            .replaceAll("")
            .lines()
            .filter(line -> line.contains("- becomes +"))
            .map(line -> line.substring(0, line.indexOf(' ')))
            .findFirst()
            .orElseThrow(() -> new AssertionError(mutants.out()));
    var verify = Samples.mutastic("verify", id, project.toString());
    assertThat(verify.out()).as(verify.err()).contains("is caught by a timeout");
    shown("timed-out.ansi", project, verify);
  }

  /// How often the courier's log says something when nothing else is: inside the slow mutant's run.
  private static final String COURIER_HEARTBEAT = "3.25";

  private static String[] withProgress(String[] run, String progress) {
    var args = new ArrayList<>(List.of(run));
    args.add("--progress=" + progress);
    return args.toArray(String[]::new);
  }

  /// The pricing sample's code and tests replaced by a courier's: postage, and a hand-over that
  /// waits at the door for a parcel that needs a signature. The test takes a second and a half, as
  /// a slow integration test does.
  private static Path courier(Path project) throws IOException {
    try (Stream<Path> files = Files.walk(project.resolve("src"))) {
      for (var file : files.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(file);
      }
    }
    var main = Files.createDirectories(project.resolve("src/main/java/com/example/courier"));
    var test = Files.createDirectories(project.resolve("src/test/java/com/example/courier"));
    Files.writeString(
        project.resolve("settings.gradle.kts"),
        Files.readString(project.resolve("settings.gradle.kts"))
            .replace("rootProject.name = \"pricing\"", "rootProject.name = \"courier\""));
    Files.writeString(
        main.resolve("Postage.java"),
        """
        package com.example.courier;

        public final class Postage {
          private Postage() {}

          /// 3 up to a kilo, 5 above; 2 more for a fragile parcel, and twice that by express.
          public static int of(int grams, boolean fragile, boolean express) {
            int postage = grams > 1000 ? 5 : 3;
            if (fragile) {
              postage += 2;
            }
            return express ? postage * 2 : postage;
          }
        }
        """);
    Files.writeString(
        main.resolve("Signature.java"),
        """
        package com.example.courier;

        public final class Signature {
          private Signature() {}

          /// A parcel that needs a signature waits for someone to come to the door.
          public static void waitFor(boolean needed) throws InterruptedException {
            if (needed) {
              Thread.sleep(3000);
            }
          }
        }
        """);
    Files.writeString(
        test.resolve("RoundTest.java"),
        """
        package com.example.courier;

        import static org.junit.jupiter.api.Assertions.assertEquals;

        import org.junit.jupiter.api.Test;

        class RoundTest {
          @Test
          void aRound() throws InterruptedException {
            Thread.sleep(1000); // the van takes a while to get round
            assertEquals(3, Postage.of(500, false, false));
            assertEquals(14, Postage.of(2000, true, true));
            Signature.waitFor(false);
          }
        }
        """);
    return project;
  }

  /// The terminal as it stood at the last frame the live block drew with a worker marked `SLOW`:
  /// every permanent line so far, then that frame's block. Each write is a frame, between the
  /// synchronized output's marks: what clears the last block, any permanent lines, then the block,
  /// drawn with wrapping off.
  static String screenAtSlow(String err) {
    var said = new StringBuilder();
    String screen = null;
    for (var frame : err.split(Pattern.quote(ProgressStream.BEGIN_FRAME), -1)) {
      var text = frame.replace(ProgressStream.END_FRAME, "");
      int open = text.indexOf(ProgressStream.NO_WRAP);
      var permanent = open < 0 ? text : text.substring(0, open);
      said.append(CLEARING.matcher(permanent).replaceAll(""));
      if (open >= 0) {
        var block = text.substring(open + ProgressStream.NO_WRAP.length());
        int close = block.indexOf(ProgressStream.WRAP);
        block = close < 0 ? block : block.substring(0, close);
        if (block.contains("SLOW")) {
          screen = said + block + "\n";
        }
      }
    }
    assertThat(screen).as(err).isNotNull();
    return screen;
  }

  /// What clears the live block, and the tab's progress, which a terminal doesn't show as text.
  private static final Pattern CLEARING =
      Pattern.compile(
          "\r?\u001b\\[2K|\u001b\\[1A|\u001b\\[\\d*[AJK]|\u001b]9;4;[^\u001b]*\u001b\\\\");

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
    shown("test-first-red.ansi", project, fromItsMessage(red));

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

  /// The agent guide's cycle, as the agent and its gate see it: the test-first guide's code on the
  /// pricing sample in a repository of its own, with the gate opened as the plugin's hook opens it,
  /// the agent format's run on the cycle's lines once the code passes, and the gate's check once
  /// the card's tests are in. The guide's `verify` is the test-first guide's.
  @Test
  void anAgentsTestFirstCycle() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    Files.writeString(project.resolve(".gitignore"), "build/\n.gradle/\n");
    git(project, "init", "-q");
    git(project, "add", "-A");
    git(project, "commit", "-q", "-m", "start");
    var steps = marked(PAGES.resolve("guides/test-first.mdx"));
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    var code = project.resolve("src/main/java/com/example/pricing/Pricing.java");
    var stdin = System.in;
    Samples.Result opened;
    try {
      System.setIn(
          new java.io.ByteArrayInputStream(
              "{\"session_id\":\"docs\"}".getBytes(StandardCharsets.UTF_8)));
      opened = Samples.mutastic("gate", "open", project.toString(), "--hook=claude");
    } finally {
      System.setIn(stdin);
    }
    assertThat(opened.exit()).as(opened.err()).isEqualTo(Main.PASSED);
    shown("test-first-agent-open.txt", project, opened);

    added(test, steps.get(0));
    added(code, steps.get(1));
    changed(code, steps.get(2));
    var run =
        Samples.mutastic(
            "run", project.toString(), "--since=HEAD", "--format=agent", "--workers=1");
    assertThat(run.exit()).as(run.err()).isEqualTo(Main.FAILED);
    assertThat(run.out()).contains("done when: mutastic verify m809c9c9b,md0b32a4f ");
    shown("test-first-agent-run.txt", project, run);

    added(test, steps.get(3));
    var check = Samples.mutastic("gate", "check", project.toString());
    assertThat(check.exit()).as(check.out()).isEqualTo(Main.PASSED);
    assertThat(check.out()).startsWith("mutastic gate: passed");
    shown("test-first-agent-check.txt", project, check);
  }

  /// The refactor guide's cycles, from where the test-first guide's ends, committed: for each tier
  /// its failing test, the `if` that passes it, the run that misses its edge, and the card's test
  /// with `verify`; then, before the last commit, the run that starts the refactor, the refactor,
  /// and the run after it, which catches every mutant of the new shape.
  @Test
  void aRefactor() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    var code = project.resolve("src/main/java/com/example/pricing/Pricing.java");
    var first = marked(PAGES.resolve("guides/test-first.mdx"));
    added(test, first.get(0));
    added(code, first.get(1));
    changed(code, first.get(2));
    added(test, first.get(3));
    git(project, "init", "-q");
    git(project, "add", "-A");
    git(project, "commit", "-q", "-m", "start");
    var page = PAGES.resolve("guides/test-first-refactor.mdx");
    var steps = marked(page);
    var testPath = "samples/pricing/" + project.relativize(test);
    var codePath = "samples/pricing/" + project.relativize(code);
    assertThat(steps)
        .extracting(Marked::kind, Marked::path)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("added to", testPath),
            org.assertj.core.groups.Tuple.tuple("changed in", codePath),
            org.assertj.core.groups.Tuple.tuple("added to", testPath),
            org.assertj.core.groups.Tuple.tuple("added to", testPath),
            org.assertj.core.groups.Tuple.tuple("changed in", codePath),
            org.assertj.core.groups.Tuple.tuple("added to", testPath),
            org.assertj.core.groups.Tuple.tuple("changed in", codePath));

    aTier(project, page, "100", steps.subList(0, 3));
    var cycle =
        Samples.mutastic(TERMINAL, "run", project.toString(), "--since=HEAD", "--workers=1");
    assertThat(cycle.exit()).as(cycle.out()).isEqualTo(Main.PASSED);
    git(project, "commit", "-q", "-a", "-m", "10 off from 100");
    aTier(project, page, "250", steps.subList(3, 6));

    var before =
        Samples.mutastic(TERMINAL, "run", project.toString(), "--since=HEAD", "--workers=1");
    assertThat(before.exit()).as(before.out()).isEqualTo(Main.PASSED);
    shown("test-first-refactor-before.ansi", project, before);
    changed(code, steps.get(6));
    var imported = "import java.util.List;";
    assertThat(Files.readString(page)).contains("`" + imported + "` at the top of the file");
    Files.writeString(
        code,
        Files.readString(code).replaceFirst("(?m)^(package .*;\\n)", "$1\n" + imported + "\n"));
    var after =
        Samples.mutastic(TERMINAL, "run", project.toString(), "--since=HEAD", "--workers=1");
    assertThat(after.exit()).as(after.out()).isEqualTo(Main.PASSED);
    shown("test-first-refactor-after.ansi", project, after);
  }

  /// One tier's cycle on the refactor guide, up to its commit: its failing test, the `if` that
  /// passes it, the run that misses its edge, which `verify` on the page names, and the card's test
  /// with `verify`.
  private static void aTier(Path project, Path page, String tier, List<Marked> steps)
      throws Exception {
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    var code = project.resolve("src/main/java/com/example/pricing/Pricing.java");
    added(test, steps.get(0));
    var red = Samples.mutastic(TERMINAL, "run", project.toString());
    assertThat(red.exit()).as(red.err()).isEqualTo(Main.TESTS_FAIL);
    assertThat(red.err()).contains("From" + tier);
    shown("test-first-refactor-red-" + tier + ".ansi", project, fromItsMessage(red));

    changed(code, steps.get(1));
    var run = Samples.mutastic(TERMINAL, "run", project.toString(), "--since=HEAD", "--workers=1");
    assertThat(run.exit()).as(run.err()).isEqualTo(Main.FAILED);
    shown("test-first-refactor-run-" + tier + ".ansi", project, run);
    var done = DONE_WHEN.matcher(ANSI.matcher(run.out()).replaceAll(""));
    assertThat(done.find()).as(run.out()).isTrue();
    var ids = done.group("ids");
    assertThat(Files.readString(page)).contains("mutastic verify " + ids + " samples/pricing\n");

    added(test, steps.get(2));
    shown(
        "test-first-refactor-verify-" + tier + ".ansi",
        project,
        mutastic("verify", ids, project.toString()));
  }

  /// `block`'s code in place of the method in `file` it declares, with that method's comment.
  private static void changed(Path file, Marked block) throws IOException {
    var signature =
        block.code().lines().filter(line -> line.startsWith("public ")).findFirst().orElseThrow();
    var source = Files.readString(file);
    var at = source.indexOf("  " + signature + "\n");
    assertThat(at).as("%s in %s", signature, file).isNotNegative();
    var start = source.lastIndexOf("\n\n", at) + 2;
    var end = source.indexOf("\n  }\n", at) + "\n  }\n".length();
    Files.writeString(
        file, source.substring(0, start) + block.code().indent(2) + source.substring(end));
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

  /// The coverage page's example. The checkout sample's tests check too little, and the page's
  /// check the results: JaCoCo measures the same line and branch coverage for both, as the page's
  /// table says, while mutastic misses mutants with the first that the second catches, all but the
  /// boundary. The edge's tests catch that, and `stronger` finds what every test buying one item
  /// hides, which the page's last test catches. Each run is on a copy of its own, so none is
  /// compared with the one before.
  @Test
  void coverageAndMutants() throws Exception {
    var page = PAGES.resolve("concepts/coverage-and-mutants.mdx");
    var text = Files.readString(page);
    var steps = marked(page).stream().filter(block -> !block.kind().equals("from")).toList();
    var path = "samples/checkout/" + CHECKOUT_TEST;
    assertThat(steps)
        .extracting(Marked::kind, Marked::path)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("changed in", path),
            org.assertj.core.groups.Tuple.tuple("added to", path),
            org.assertj.core.groups.Tuple.tuple("added to", path));

    var weak = checkout("weak", steps.subList(0, 0));
    var tooLittle = jacoco(weak);
    assertThat(text).contains("| Checking too little | " + tooLittle + " |");
    var concise = concise(weak);
    assertThat(concise.exit()).as(concise.out()).isEqualTo(Main.FAILED);
    shown("coverage-weak.ansi", weak, concise);
    var cards = checkout("cards", steps.subList(0, 0));
    var full = Samples.mutastic(TERMINAL, "run", cards.toString(), "--workers=1");
    shown(
        "coverage-arithmetic.ansi",
        cards,
        aCard(full, "tests pass even when * becomes / in pay()"));
    shown("coverage-returns.ansi", cards, aCard(full, "no test checks what pay() returns here"));
    shown("coverage-calls.ansi", cards, aCard(full, "no test checks the effect of calling add()"));

    var strong = checkout("strong", steps.subList(0, 1));
    var checking = jacoco(strong);
    assertThat(checking).isEqualTo(tooLittle);
    assertThat(text).contains("| Checking the results | " + checking + " |");
    var run = Samples.mutastic(TERMINAL, "run", strong.toString(), "--workers=1");
    assertThat(run.exit()).as(run.out()).isEqualTo(Main.PASSED);
    assertThat(ANSI.matcher(run.out()).replaceAll(""))
        .containsOnlyOnce("✗ MISSED")
        .contains("no test checks discount() at total == 100");
    shown("coverage-strong.ansi", strong, run);

    var edges = checkout("edges", steps.subList(0, 2));
    var caught = Samples.mutastic(TERMINAL, "run", edges.toString(), "--workers=1");
    assertThat(caught.exit()).as(caught.out()).isEqualTo(Main.PASSED);
    assertThat(ANSI.matcher(caught.out()).replaceAll("")).doesNotContain("✗ MISSED");
    shown("coverage-edges.ansi", edges, caught);
    shown(
        "coverage-stronger-mutants.ansi",
        edges,
        mutastic("mutants", edges.toString(), "--operators=stronger"));
    var more =
        Samples.mutastic(TERMINAL, "run", edges.toString(), "--operators=stronger", "--workers=1");
    assertThat(more.exit()).as(more.out()).isEqualTo(Main.PASSED);
    var plain = ANSI.matcher(more.out()).replaceAll("");
    assertThat(plain).containsOnlyOnce("✗ MISSED").contains("+ int total = price;");
    var done = DONE_WHEN.matcher(plain);
    assertThat(done.find()).as(more.out()).isTrue();
    var ids = done.group("ids");
    assertThat(text).contains("mutastic verify " + ids + " samples/checkout\n");
    shown("coverage-stronger.ansi", edges, more);
    added(edges.resolve(CHECKOUT_TEST), steps.get(2));
    shown("coverage-stronger-verify.ansi", edges, mutastic("verify", ids, edges.toString()));
  }

  /// A copy of the checkout sample, under `name`, with the coverage page's `steps` made to its
  /// tests.
  private Path checkout(String name, List<Marked> steps) throws IOException {
    var project = Samples.copy(CHECKOUT, work.resolve(name).resolve("checkout"));
    var test = project.resolve(CHECKOUT_TEST);
    for (Marked step : steps) {
      if (step.kind().equals("added to")) {
        added(test, step);
      } else {
        replacedTests(test, step);
        imported(test);
      }
    }
    return project;
  }

  /// The imports the coverage page says the tests that check the results need, added to `file`.
  private static void imported(Path file) throws IOException {
    var page = Files.readString(PAGES.resolve("concepts/coverage-and-mutants.mdx"));
    var source = Files.readString(file);
    for (String[] after :
        List.of(
            new String[] {
              "import static org.junit.jupiter.api.Assertions.assertTrue;",
              "import static org.junit.jupiter.api.Assertions.assertEquals;"
            },
            new String[] {"import java.util.ArrayList;", "import java.util.List;"})) {
      assertThat(page).contains("`" + after[1] + "`");
      assertThat(source).contains(after[0] + "\n");
      source = source.replace(after[0] + "\n", after[1] + "\n" + after[0] + "\n");
    }
    Files.writeString(file, source);
  }

  /// Each test in `block` in place of the one of its name in `file`.
  private static void replacedTests(Path file, Marked block) throws IOException {
    var source = Files.readString(file);
    for (String test : BLANK_LINE.splitAsStream(block.code().strip()).toList()) {
      var name = test.lines().filter(line -> line.startsWith("void ")).findFirst().orElseThrow();
      var at = source.indexOf("  " + name + "\n");
      assertThat(at).as("%s in %s", name, file).isNotNegative();
      var start = source.lastIndexOf("  @Test\n", at);
      var end = source.indexOf("\n  }\n", at) + "\n  }\n".length();
      source = source.substring(0, start) + test.indent(2) + source.substring(end);
    }
    Files.writeString(file, source);
  }

  /// JaCoCo's line and branch coverage of the checkout sample, as the coverage page's table shows
  /// it, `8 of 8 | 2 of 2`, from the CSV report its build writes. Every line and branch is covered.
  private static String jacoco(Path project) throws Exception {
    var exit =
        new ProcessBuilder(project.resolve("gradlew").toString(), "-q", "test", "jacocoTestReport")
            .directory(project.toFile())
            .inheritIO()
            .start()
            .waitFor();
    assertThat(exit).as("./gradlew test jacocoTestReport").isZero();
    var csv = Files.readAllLines(project.resolve("build/reports/jacoco/test/jacocoTestReport.csv"));
    assertThat(csv).as("one class").hasSize(2);
    var header = COMMA.splitAsStream(csv.get(0)).toList();
    var row = COMMA.splitAsStream(csv.get(1)).toList();
    java.util.function.ToIntFunction<String> count =
        column -> Integer.parseInt(row.get(header.indexOf(column)));
    assertThat(count.applyAsInt("LINE_MISSED")).as("lines missed").isZero();
    assertThat(count.applyAsInt("BRANCH_MISSED")).as("branches missed").isZero();
    return "%d of %d | %d of %d"
        .formatted(
            count.applyAsInt("LINE_COVERED"),
            count.applyAsInt("LINE_COVERED"),
            count.applyAsInt("BRANCH_COVERED"),
            count.applyAsInt("BRANCH_COVERED"));
  }

  /// The card whose headline starts `headline`, without the run's summary after the last.
  private static Samples.Result aCard(Samples.Result result, String headline) {
    var card = theCard(result, headline);
    var lines = card.out().lines().toList();
    var end = 0;
    while (end < lines.size() && !ANSI.matcher(lines.get(end)).replaceAll("").contains("Next:")) {
      end++;
    }
    var shown = String.join("\n", lines.subList(0, end)).stripTrailing() + "\n";
    return new Samples.Result(result.exit(), shown, result.err());
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
    shown("configured-arid-explain.ansi", project, mutastic("explain", ARID, project.toString()));
  }

  /// The Equivalent mutants page's fast-path guard: a check that only picks between a fast way and
  /// a safe way, whose mutants every test passes, as both ways give the same answer; and one of
  /// them ignored as equivalent.
  @Test
  void aCheckThatOnlyPicksBetweenAFastWayAndASafeWay() throws Exception {
    var project = Samples.copy(FASTPATH, work.resolve("fastpath"));
    var run = Samples.mutastic(TERMINAL, "run", project.toString(), "--workers=1");
    assertThat(run.exit()).as(run.out()).isEqualTo(Main.PASSED);
    var plain = ANSI.matcher(run.out()).replaceAll("");
    assertThat(plain).containsOnlyOnce("✗ MISSED").contains("· " + ALWAYS_THE_SAFE_WAY);
    shown(
        "fastpath-missed.ansi",
        project,
        aCard(run, "no test checks read() at digits.length() == 9"));
    shown(
        "fastpath-ignore.ansi",
        project,
        mutastic(
            "ignore",
            TO_THE_SAFE_WAY_AT_9,
            "--as=equivalent",
            "--reason=" + SAME_AT_9,
            project.toString()));
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
    shown("id-elsewhere.ansi", project, fromItsMessage(elsewhere));
    shown("tests-fail.ansi", project, fromItsMessage(failing));
  }

  /// Troubleshooting's examples of a multi-module build run without a module, and with one it
  /// hasn't got: the Maven sample of two modules.
  @Test
  void aBuildOfModules() throws Exception {
    var project =
        Samples.copy(SAMPLE.resolveSibling("maven-modules"), work.resolve("maven-modules"));

    var root = Samples.mutastic(TERMINAL, "run", project.toString());
    var missing = Samples.mutastic(TERMINAL, "run", project.toString(), ":nope");

    assertThat(root.exit()).isEqualTo(Main.USAGE);
    assertThat(missing.exit()).isEqualTo(Main.USAGE);
    shown("module-root.ansi", project, fromItsMessage(root));
    shown("module-missing.ansi", project, fromItsMessage(missing));
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

  /// What mutastic says on stderr from its message's first line, a message of more than one.
  static Samples.Result fromItsMessage(Samples.Result result) {
    var lines = result.err().lines().toList();
    var first = lines.stream().filter(line -> line.startsWith("mutastic: ")).findFirst();
    assertThat(first).as(result.err()).isPresent();
    var said = lines.subList(lines.indexOf(first.get()), lines.size());
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
  static void shown(String name, Path project, Samples.Result result) throws Exception {
    var sample = "samples/" + project.getFileName();
    // Its real path first: a build tool names it so, through a link such as macOS's /var.
    var output =
        result
            .out()
            .replace(project.toRealPath().toString(), sample)
            .replace(project.toString(), sample);
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

  /// The live block's: the spinner's frame, which follows the clock, and the bar, whose width gives
  /// way to the clock and what's left.
  private static final Pattern SPINNER = Pattern.compile("[⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏]");

  private static final Pattern BAR = Pattern.compile("━+((?:\u001B\\[[0-9;]*m)*)─*");

  /// The version a run names as it starts, `dev` in a build of main.
  private static final Pattern VERSION =
      Pattern.compile("^mutastic \\S+ · reading", Pattern.MULTILINE);

  private static String withoutTimings(String output) {
    output = VERSION.matcher(output).replaceAll("mutastic … · reading");
    var drawn = BAR.matcher(SPINNER.matcher(output).replaceAll("⠋")).replaceAll("━$1─");
    var steady = COMMITS.matcher(TIMINGS.matcher(drawn).replaceAll("…")).replaceAll("$1…");
    return FENCE.matcher(steady).replaceAll("${marker}3f9a0c1e>>");
  }
}
