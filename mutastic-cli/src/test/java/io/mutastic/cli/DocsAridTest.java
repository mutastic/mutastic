package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/// The pages about arid code show what mutastic does, and say what's true of it. The Arid rules
/// page explains a skipped mutant of each rule it has a section for, on `samples/arid`, and each
/// section's "What it can hide" is checked by running that rule's mutants with
/// `MUTASTIC_ARID=audit` against the sample's tests, which check what the skip could hide: one at
/// least must be caught by an assertion. The guide takes `samples/sales` through a run, its audit,
/// an ignore and the `mutate:` rule it adds.
///
/// To write their output: `./gradlew :mutastic-cli:exportDocsOutput`.
class DocsAridTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));
  private static final Path PAGES = Path.of(System.getProperty("mutastic.docs"));
  private static final Path RULES = PAGES.getParent().getParent().resolve("data/arid.json");

  /// What a person at a terminal sees.
  private static final Map<String, String> TERMINAL =
      Map.of("FORCE_COLOR", "1", "MUTASTIC_FORMAT", "full");

  private static final Map<String, String> AUDIT =
      Map.of("FORCE_COLOR", "1", "MUTASTIC_FORMAT", "full", "MUTASTIC_ARID", "audit");

  /// A rule's section on the page: `<AridRule id="mutastic:waits" />`.
  private static final Pattern SECTION = Pattern.compile("(?m)^<AridRule id=\"([^\"]+)\"");

  /// The mutant each section explains, by its change, when its rule skips more than one.
  private static final Map<String, String> SHOWN =
      Map.ofEntries(
          Map.entry("mutastic:trivial-accessors", "`setName()` does nothing"),
          Map.entry("mutastic:debug-representations", "`/` becomes `*`"),
          Map.entry("mutastic:output", "the call to `println()` is removed"),
          Map.entry("mutastic:messages", "`\"amount must not be negative\"` becomes `\"\"`"),
          Map.entry("mutastic:failed-asserts", "the `throw` of `AssertionError` is removed"),
          Map.entry("mutastic:capacity", "`*` becomes `/`"),
          Map.entry("mutastic:wait-times", "`seconds * 1000` becomes `seconds`"),
          Map.entry("mutastic:waits", "the call to `sleep()` is removed"),
          Map.entry("mutastic:null-checks", "the call to `requireNonNull()` is removed"),
          Map.entry("mutastic:array-sizes", "the constant `7` becomes `8`"),
          Map.entry("mutastic:first-values", "the constant `0` becomes `1`"),
          Map.entry("mutastic:split-limits", "the constant `2` becomes `3`"),
          Map.entry("mutastic:thread-names", "the string `\"report-worker\"` becomes `\"\"`"),
          Map.entry("mutastic:thread-priorities", "the call to `setPriority()` is removed"),
          Map.entry("mutastic:keep-alive", "the constant `30` becomes `29`"),
          Map.entry("mutastic:stack-sizes", "the constant `67108864` becomes `1`"));

  /// The rules whose section says a test of what the code does can't see what they skip: the audit
  /// must find no catch by an assertion.
  private static final java.util.Set<String> UNSEEN = java.util.Set.of("mutastic:capacity");

  /// An arid audit's count of catches by an assertion, one or more.
  private static final Pattern CAUGHT_BY_AN_ASSERTION = Pattern.compile("\\([1-9]\\d* by an");

  private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-9;]*m");

  @TempDir Path work;

  @Test
  void eachRuleOnThePageIsExplainedAndWhatItCanHideIsCaught() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("arid"), work.resolve("arid"));
    var mutants = aridMutants(project);
    var page = Files.readString(PAGES.resolve("reference/arid.mdx"));
    var sections = SECTION.matcher(page).results().map(found -> found.group(1)).toList();

    assertThat(sections).as("the rules with a section on reference/arid.mdx").isNotEmpty();
    for (String id : sections) {
      var ids = mutants.getOrDefault(id, List.of());
      assertThat(ids).as("%s: samples/arid needs a class with a mutant it skips", id).isNotEmpty();
      var shown = ids.stream().filter(mutant -> mutant.change().contains(SHOWN.get(id))).toList();
      assertThat(shown)
          .as("%s: the mutant its section explains, %s", id, SHOWN.get(id))
          .isNotEmpty();
      var explained =
          Samples.mutastic(TERMINAL, "explain", shown.getFirst().id(), project.toString());
      DocsOutputTest.shown("arid/" + anchor(id) + ".ansi", project, theSkip(explained));

      var only = String.join(",", ids.stream().map(Mutant::id).toList());
      var audit = audited(project, "--operators=all", "--only=" + only);
      if (UNSEEN.contains(id)) {
        assertThat(audit)
            .as("%s's What it can hide: nothing a test's assertion catches", id)
            .contains("(0 by an assertion)");
      } else {
        assertThat(audit)
            .as("%s's What it can hide: a test in samples/arid catches one by an assertion", id)
            .containsPattern(CAUGHT_BY_AN_ASSERTION);
      }
    }
  }

  /// The guide: the run, the format string and the output explained, the audit, an ignore of a
  /// value logged and recorded as a metric, and the run after the page's `mutate:` rule.
  @Test
  void theGuidesClass() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("sales"), work.resolve("sales"));
    var page = Files.readString(PAGES.resolve("guides/arid-in-practice.mdx"));
    // The page's run first: a run with other operators before it would change what it compares.
    DocsOutputTest.shown("arid-run.ansi", project, concise(TERMINAL, project));
    var events = events(project, "--operators=stronger");
    var join = theMutant(events, "the call to `join()` is removed");
    var printed = theMutant(events, "the call to `println()` is removed");
    var format = theMutant(events, "the string `\"summed {} amounts in {} ms\"` becomes `\"\"`");
    var logged =
        theMutants(
            events, "`-` becomes `+`", "`/` becomes `*`", "the call to `record()` is removed");

    assertThat(join.reason()).as("a removed timed join counts").isEmpty();
    assertThat(printed.reason()).isEqualTo("arid: only writes output");
    assertThat(format.reason()).isEqualTo("arid: only writes output");
    assertThat(logged).allSatisfy(mutant -> assertThat(mutant.reason()).isEmpty());
    assertThat(page)
        .contains("mutastic explain " + format.id() + " samples/sales")
        .contains("mutastic explain " + printed.id() + " samples/sales")
        .contains("mutastic ignore " + logged.getFirst().id() + " samples/sales");

    DocsOutputTest.shown("arid-format-string.ansi", project, explained(project, format));
    DocsOutputTest.shown("arid-explain.ansi", project, explained(project, printed));
    var audit = concise(AUDIT, project);
    assertThat(audit.out()).containsPattern(CAUGHT_BY_AN_ASSERTION);
    DocsOutputTest.shown("arid-audit.ansi", project, theSummary(audit, "Mutants", "Arid audit"));

    var ignored = ignoredAsTelemetry(project, page, logged);
    DocsOutputTest.shown("arid-ignore.ansi", project, ignored);

    Files.writeString(project.resolve("mutastic.yaml"), theRuleItAdds(page));
    var kept = concise(TERMINAL, project);
    assertThat(kept.exit()).as(kept.out() + kept.err()).isEqualTo(Main.PASSED);
    DocsOutputTest.shown("arid-kept.ansi", project, kept);
  }

  private static Samples.Result concise(Map<String, String> env, Path project) {
    return Samples.mutastic(env, "run", project.toString(), "--format=concise", "--workers=1");
  }

  private static Samples.Result explained(Path project, Mutant mutant) {
    return theSkip(Samples.mutastic(TERMINAL, "explain", mutant.id(), project.toString()));
  }

  /// Each mutant ignored as the page ignores the first, with its reason; what the first said.
  private static Samples.Result ignoredAsTelemetry(Path project, String page, List<Mutant> logged) {
    var reason = "timing only, checked in dashboards";
    assertThat(page).contains("--as=telemetry --reason=\"" + reason + "\"");
    var said = new ArrayList<Samples.Result>();
    for (Mutant mutant : logged) {
      var result =
          Samples.mutastic(
              TERMINAL,
              "ignore",
              mutant.id(),
              project.toString(),
              "--as=telemetry",
              "--reason=" + reason);
      assertThat(result.exit()).as(result.err()).isEqualTo(Main.PASSED);
      said.add(result);
    }
    return said.getFirst();
  }

  /// A mutant: its id, its change in words, and why it was skipped, if it was.
  private record Mutant(String id, String change, String reason) {}

  /// The arid mutants of a run with every operator, by the rule that skips each.
  private static Map<String, List<Mutant>> aridMutants(Path project) throws IOException {
    var rules = rules();
    var byRule = new LinkedHashMap<String, List<Mutant>>();
    for (Mutant mutant : events(project, "--operators=all")) {
      if (!mutant.reason().startsWith("arid:")) {
        continue;
      }
      var rule =
          rules.entrySet().stream()
              .filter(
                  each ->
                      each.getValue().stream()
                          .anyMatch(reason -> reason.matcher(mutant.reason()).matches()))
              .map(Map.Entry::getKey)
              .findFirst();
      assertThat(rule).as("the rule that gives %s, in arid.json", mutant.reason()).isPresent();
      byRule.computeIfAbsent(rule.get(), key -> new ArrayList<>()).add(mutant);
    }
    return byRule;
  }

  /// Each rule in arid.json, by id, with its reasons as patterns: `<field>` is any name.
  private static Map<String, List<Pattern>> rules() throws IOException {
    var rules = new HashMap<String, List<Pattern>>();
    for (JsonNode rule : JsonMapper.builder().build().readTree(RULES.toFile()).path("rules")) {
      var patterns = new ArrayList<Pattern>();
      for (JsonNode reasons : rule.path("reasons")) {
        for (JsonNode reason : reasons) {
          var parts = reason.asString().split("<[a-z]+>", -1);
          var pattern = new StringBuilder();
          for (int index = 0; index < parts.length; index++) {
            pattern.append(index > 0 ? "[^`\\s]+" : "").append(Pattern.quote(parts[index]));
          }
          patterns.add(Pattern.compile(pattern.toString()));
        }
      }
      rules.put(rule.path("id").asString(), patterns);
    }
    return rules;
  }

  private static String anchor(String id) {
    return id.substring(id.indexOf(':') + 1);
  }

  /// Every mutant of a run, from its events.
  private static List<Mutant> events(Path project, String operators) {
    var result =
        Samples.mutastic(
            Map.of(), "run", project.toString(), operators, "--format=jsonl", "--workers=1");
    var mapper = JsonMapper.builder().build();
    return result
        .out()
        .lines()
        .map(mapper::readTree)
        .filter(event -> event.path("reason").asString("").equals("mutant-finished"))
        .map(
            event ->
                new Mutant(
                    event.path("id").asString(),
                    event.path("mutation").path("description").asString(""),
                    event.path("statusReason").asString("")))
        .toList();
  }

  private static Mutant theMutant(List<Mutant> mutants, String change) {
    var found = mutants.stream().filter(mutant -> mutant.change().equals(change)).toList();
    assertThat(found).as("the mutant: %s", change).hasSize(1);
    return found.getFirst();
  }

  private static List<Mutant> theMutants(List<Mutant> mutants, String... changes) {
    var found = new ArrayList<Mutant>();
    for (String change : changes) {
      found.add(
          mutants.stream()
              .filter(mutant -> mutant.change().equals(change))
              .filter(mutant -> !mutant.reason().startsWith("arid:"))
              .findFirst()
              .orElseThrow(() -> new AssertionError("no counted mutant: " + change)));
    }
    return found;
  }

  private static String audited(Path project, String... options) {
    var args =
        new ArrayList<>(List.of("run", project.toString(), "--format=concise", "--workers=1"));
    args.addAll(List.of(options));
    var result = Samples.mutastic(AUDIT, args.toArray(String[]::new));
    return ANSI.matcher(result.out()).replaceAll("");
  }

  /// What `explain` says of a skipped mutant: the skip and its mutant, then its rule, with the docs
  /// and the `mutate:` rule that brings it back; not the run before it or the bytecode.
  private static Samples.Result theSkip(Samples.Result result) {
    var lines = result.out().lines().toList();
    int first = indexOf(lines, 0, line -> line.contains("SKIPPED"));
    int about = indexOf(lines, first, line -> line.contains("About this mutant"));
    int rule = indexOf(lines, about, line -> line.strip().startsWith("= arid"));
    int end = indexOf(lines, rule + 1, line -> line.strip().startsWith("= "));
    var shown =
        String.join("\n", lines.subList(first, about)).stripTrailing()
            + "\n\n"
            + String.join("\n", lines.subList(rule, end))
            + "\n";
    return new Samples.Result(result.exit(), shown, result.err());
  }

  /// The summary's rows from the one starting `from` to the one starting `to`, and the lines that
  /// go on from that one.
  private static Samples.Result theSummary(Samples.Result result, String from, String to) {
    var lines = result.out().lines().toList();
    int first = indexOf(lines, 0, line -> line.strip().startsWith(from));
    int last = indexOf(lines, first, line -> line.strip().startsWith(to));
    int end = last + 1;
    while (end < lines.size() && plain(lines.get(end)).startsWith("             ")) {
      end++;
    }
    var shown = String.join("\n", lines.subList(first, end)) + "\n";
    return new Samples.Result(result.exit(), shown, result.err());
  }

  /// The first of `lines` from `from` on that `matches`, its colours left out.
  private static int indexOf(List<String> lines, int from, Predicate<String> matches) {
    for (int index = from; index < lines.size(); index++) {
      if (matches.test(plain(lines.get(index)))) {
        return index;
      }
    }
    throw new AssertionError("no such line from " + from + " in:\n" + String.join("\n", lines));
  }

  /// The page's `mutastic.yaml`, the block it adds to the sample.
  private static String theRuleItAdds(String page) {
    var block =
        Pattern.compile(
                "\\{/\\* added to: samples/sales/mutastic.yaml \\*/}\\n```yaml\\n((?s:.*?))^```$",
                Pattern.MULTILINE)
            .matcher(page);
    assertThat(block.find()).as("the guide's mutastic.yaml").isTrue();
    return block.group(1);
  }

  private static String plain(String line) {
    return ANSI.matcher(line).replaceAll("");
  }
}
