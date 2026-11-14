package io.mutastic.report;

import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.regex.Pattern;

/// The run as files other tools read:
///
/// - `json`: the mutation-testing report schema (version 2), which report viewers, dashboards and
///   badges understand, with what mutastic knows beyond it under `mutastic` keys
///   (`docs/public/schemas/report/v1.json`).
/// - `html`: that JSON in the schema's own viewer, embedded, so it opens offline from disk.
/// - `xml`: the `mutations.xml` format many build-server and code-quality plugins read.
/// - `sarif`: SARIF 2.1.0, which code scanning reads ([Sarif]).
public final class ReportFiles {
  public static final List<String> KINDS = List.of("json", "html", "xml", "sarif");

  private final Path root;
  private final SourceView sources;
  private final int threshold;
  private final String version;
  private final OperatorDocs docs;
  private final Optional<Run> run;

  /// What the run was beyond its results, for the report's own page: when it started, and whether
  /// it passed as its exit code says (on the threshold, or with `--fail-on-new` on new misses).
  public record Run(Instant startedAt, boolean passed) {}

  /// `root` is the project directory: file paths in the reports are relative to it.
  public ReportFiles(Path root, SourceView sources, int threshold, String version) {
    this(root, sources, threshold, version, OperatorDocs.LATEST);
  }

  /// @param docs where each operator is described, for SARIF's rules and the report page to link to
  public ReportFiles(
      Path root, SourceView sources, int threshold, String version, OperatorDocs docs) {
    this(root, sources, threshold, version, docs, Optional.empty());
  }

  private ReportFiles(
      Path root,
      SourceView sources,
      int threshold,
      String version,
      OperatorDocs docs,
      Optional<Run> run) {
    this.root = root;
    this.sources = sources;
    this.threshold = threshold;
    this.version = version;
    this.docs = docs;
    this.run = run;
  }

  /// The same reports, with what's known of the run that made them.
  public ReportFiles withRun(Run run) {
    return new ReportFiles(root, sources, threshold, version, docs, Optional.of(run));
  }

  /// Writes the report of `kind` to `file`.
  public void write(String kind, Report report, Path file) {
    var text =
        switch (kind) {
          case "json" -> Json.write(json(report));
          case "html" -> html(report);
          case "xml" -> xml(report);
          case "sarif" -> Json.write(new Sarif(root, sources, version, docs).document(report));
          default -> throw new IllegalArgumentException("unknown report " + kind);
        };
    try {
      Files.createDirectories(file.toAbsolutePath().getParent());
      Files.writeString(file, text);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// A source file's entry: its text and the mutants in it.
  private record SourceFile(String source, List<Map<String, Object>> mutants) {
    Map<String, Object> json() {
      var entry = new LinkedHashMap<String, Object>();
      entry.put("language", "java");
      entry.put("source", source);
      entry.put("mutants", mutants);
      return entry;
    }
  }

  /// The report-schema document. Statuses are the schema's (`Killed`, `Survived`, …); locations are
  /// 1-based with an exclusive end.
  Map<String, Object> json(Report report) {
    var document = new LinkedHashMap<String, Object>();
    document.put(
        "$schema",
        "https://raw.githubusercontent.com/stryker-mutator/mutation-testing-elements/master/packages/report-schema/src/mutation-testing-report-schema.json");
    document.put("schemaVersion", "2");
    // One threshold in mutastic: at or above it is good; the warning band is the 20 points below.
    document.put("thresholds", pair("high", threshold, "low", Math.max(0, threshold - 20)));
    document.put("projectRoot", root.toAbsolutePath().normalize().toString());
    document.put("files", files(report));
    document.put("testFiles", testFiles(report));
    document.put("framework", pair("name", "mutastic", "version", version));
    document.put("performance", performance(report));
    document.put("mutastic", runFacts(report));
    return document;
  }

  /// The commands for one mutant, as the run's own output writes them, `<id>` where its id goes.
  private static final Map<String, MutantCommand> COMMANDS = orderedCommands();

  private static Map<String, MutantCommand> orderedCommands() {
    var commands = new LinkedHashMap<String, MutantCommand>();
    commands.put("verify", MutantCommand.DONE_WHEN);
    commands.put("run", MutantCommand.REPRODUCE);
    commands.put("ignore", MutantCommand.IGNORE);
    commands.put("propose", MutantCommand.PROPOSE);
    return commands;
  }

  /// The test to add as the events have it, with the value it passes each parameter.
  private static Map<String, Object> suggestedTest(SuggestedTest test, MutantResult result) {
    var suggested = JsonEvents.suggestedTest(test, result);
    var found = result.suggestion().orElseThrow();
    var arguments = new LinkedHashMap<String, Object>();
    for (int i = 0; i < Math.min(found.parameters().size(), found.inputs().size()); i++) {
      arguments.put(found.parameters().get(i), found.inputs().get(i));
    }
    suggested.put("arguments", arguments);
    return suggested;
  }

  private Map<String, Object> commands() {
    var commands = new LinkedHashMap<String, Object>();
    COMMANDS.forEach(
        (key, command) -> commands.put(key, command.command("<id>", sources.invocation())));
    return commands;
  }

  /// Each operator in the report the docs describe, by its `mutatorName`: its page under the base.
  private static Map<String, Object> operatorDocs(Report report, OperatorDocs docs) {
    var operators = new TreeMap<String, Object>();
    for (MutantResult result : report.results()) {
      docs.url(result.mutant().operator())
          .ifPresent(
              url ->
                  operators.put(mutatorName(result.mutant()), url.substring(docs.base().length())));
    }
    return operators;
  }

  /// What mutastic knows of the run beyond the report schema: what its own page shows at the top.
  private Map<String, Object> runFacts(Report report) {
    var facts = new LinkedHashMap<String, Object>();
    facts.put("edition", "java");
    facts.put("commands", commands());
    facts.put("docsBase", docs.base());
    facts.put("operatorDocs", operatorDocs(report, docs));
    var score = report.score();
    facts.put("score", score.isPresent() ? Math.round(score.getAsDouble() * 10) / 10.0 : null);
    facts.put("threshold", threshold);
    facts.put("passed", run.map(Run::passed).orElseGet(() -> report.passes(threshold)));
    facts.put("toolchain", pair("name", "jdk", "version", String.valueOf(report.jdk())));
    run.ifPresent(
        known ->
            facts.put("startedAt", known.startedAt().truncatedTo(ChronoUnit.SECONDS).toString()));
    return facts;
  }

  /// Each source file's entry, by its path.
  private Map<String, Map<String, Object>> files(Report report) {
    var files = new TreeMap<String, SourceFile>();
    for (MutantResult result : report.results()) {
      var mutant = result.mutant();
      var file =
          files.computeIfAbsent(
              sources.file(mutant),
              path -> new SourceFile(sources.text(mutant).orElse(""), new ArrayList<>()));
      file.mutants().add(withCard(mutant(result), result));
    }
    var fileEntries = new TreeMap<String, Map<String, Object>>();
    files.forEach((path, file) -> fileEntries.put(path, file.json()));
    return fileEntries;
  }

  /// Each test class's entry, its source and the tests in it, by its path.
  private Map<String, Map<String, Object>> testFiles(Report report) {
    var testFiles = new TreeMap<String, Map<String, Object>>();
    testIdsByClass(report)
        .forEach(
            (testClass, ids) -> {
              var entry = new LinkedHashMap<String, Object>();
              var source = sources.testSource(testClass);
              source.ifPresent(text -> entry.put("source", text));
              entry.put("tests", testDefinitions(ids, source));
              testFiles.put(sources.testFile(testClass).orElse(testClass), entry);
            });
    return testFiles;
  }

  private static Map<String, List<String>> testIdsByClass(Report report) {
    var tests = new TreeMap<String, List<String>>(); // test class → test ids
    for (MutantResult result : report.results()) {
      result
          .coveredBy()
          .forEach(
              test -> tests.computeIfAbsent(testClass(test), name -> new ArrayList<>()).add(test));
    }
    return tests;
  }

  private static List<Map<String, Object>> testDefinitions(
      List<String> ids, Optional<String> source) {
    return ids.stream()
        .distinct()
        .sorted()
        .map(
            id -> {
              var test = pair("id", id, "name", testName(id));
              source
                  .flatMap(text -> testLocation(text, testName(id)))
                  .ifPresent(location -> test.put("location", location));
              return test;
            })
        .toList();
  }

  /// Where a test is declared in its source, `{"start": {"line", "column"}}` at its name: `void
  /// name(`, or Kotlin's `fun name(` and `` fun `a name`( ``. Empty where the source doesn't show
  /// it.
  static Optional<Map<String, Object>> testLocation(String source, String test) {
    var name = test.replaceFirst("[(\\[].*", "");
    if (name.isEmpty()) {
      return Optional.empty();
    }
    var declaration =
        Pattern.compile("\\b(?:void|fun)\\s+`?(?<name>" + Pattern.quote(name) + ")`?\\s*\\(");
    var lines = source.lines().toList();
    for (int i = 0; i < lines.size(); i++) {
      var found = declaration.matcher(lines.get(i));
      if (found.find()) {
        return Optional.of(Map.of("start", pair("line", i + 1, "column", found.start("name") + 1)));
      }
    }
    return Optional.empty();
  }

  /// Two fields in the order given. `Map.of` would iterate in an order that changes from one JVM to
  /// the next, and the same run should write the same file.
  private static Map<String, Object> pair(
      String firstKey, Object first, String secondKey, Object second) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put(firstKey, first);
    fields.put(secondKey, second);
    return fields;
  }

  private static Map<String, Object> performance(Report report) {
    var performance = new LinkedHashMap<String, Object>();
    performance.put("setup", 0);
    performance.put("initialRun", report.coverage().toMillis());
    performance.put("mutation", report.took().minus(report.coverage()).toMillis());
    return performance;
  }

  private Map<String, Object> mutant(MutantResult result) {
    var mutant = result.mutant();
    var advice = sources.advice(mutant);
    var place = sources.place(mutant);
    var entry = new LinkedHashMap<String, Object>();
    entry.put("id", mutant.id());
    entry.put("mutatorName", mutatorName(mutant));
    entry.put("replacement", advice.after().orElse(advice.change()));
    entry.put("description", advice.change());
    entry.put(
        "location",
        pair(
            "start", pair("line", place.line(), "column", place.start()),
            "end", pair("line", place.line(), "column", place.end())));
    entry.put("status", result.status().schemaName());
    var reason = statusReason(result, advice);
    if (!reason.isEmpty()) {
      entry.put("statusReason", reason);
    }
    entry.put("coveredBy", result.coveredBy());
    result.caughtBy().ifPresent(test -> entry.put("killedBy", List.of(test)));
    entry.put("testsCompleted", result.testsRun());
    entry.put("duration", result.took().toMillis());
    return entry;
  }

  /// A mutant's entry with what its card says, when it says anything more.
  private Map<String, Object> withCard(Map<String, Object> entry, MutantResult result) {
    var more = cardFields(result);
    if (!more.isEmpty()) {
      entry.put("mutastic", more);
    }
    return entry;
  }

  /// What the mutant's card says beyond the report schema: where, and for a miss, what the tests
  /// did, the test to write and the values they passed.
  private Map<String, Object> cardFields(MutantResult result) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("method", HumanText.methodLabel(result.mutant()));
    if (result.status() == Status.MISSED) {
      var advice = sources.advice(result);
      if (!advice.notes().isEmpty()) {
        fields.put("evidence", advice.notes());
      }
      fields.put("fix", advice.fix());
    }
    if (!result.inputs().isEmpty()) {
      fields.put("inputs", result.inputs());
    }
    SuggestedTest.of(result, sources)
        .ifPresent(test -> fields.put("suggestedTest", suggestedTest(test, result)));
    result.failureOrigin().ifPresent(origin -> fields.put("failureOrigin", origin));
    return fields;
  }

  /// `boundary` for mutastic's own operators, a plugin's by its whole id: `stream/remove-stage`.
  private static String mutatorName(Mutant mutant) {
    var id = mutant.operator().id();
    return id.startsWith("mutastic/") ? id.substring("mutastic/".length()) : id;
  }

  /// Why the mutant has its status, or empty when there's nothing to say.
  private static String statusReason(MutantResult result, Advice advice) {
    return switch (result.status()) {
      case SKIPPED -> result.mutant().skipped().orElse("");
      case CAUGHT -> result.failure().orElse("");
      case MISSED -> advice.headline();
      case TIMED_OUT -> "the tests didn't finish in time";
      case NOT_COVERED -> "no test reaches this line";
    };
  }

  /// The JSON in the schema's viewer, embedded with the viewer itself, so the page needs no
  /// network.
  String html(Report report) {
    // `</` can't appear inside a script element: `<\/` means the same in JavaScript.
    var data = Json.write(json(report)).replace("</", "<\\/");
    return """
        <!doctype html>
        <html lang="en">
        <head>
        <meta charset="utf-8">
        <meta name="viewport" content="width=device-width, initial-scale=1">
        <title>mutastic · %s</title>
        <script>
        %s
        </script>
        </head>
        <body>
        <mutation-test-report-app titlePostfix="mutastic"></mutation-test-report-app>
        <script>
        document.querySelector('mutation-test-report-app').report = %s;
        </script>
        </body>
        </html>
        """
        .formatted(escapeXml(report.project()), viewer().replace("</script", "<\\/script"), data);
  }

  /// `mutations.xml`. Its consumers key on established mutator class names, so those are what
  /// `<mutator>` carries; skipped mutants aren't listed.
  String xml(Report report) {
    var out =
        new StringBuilder(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mutations partial=\"false\">\n");
    for (MutantResult result : report.results()) {
      if (result.status() != Status.SKIPPED) {
        mutation(out, result);
      }
    }
    return out.append("</mutations>\n").toString();
  }

  private void mutation(StringBuilder out, MutantResult result) {
    var mutant = result.mutant();
    out.append(
        "<mutation detected='%s' status='%s' numberOfTestsRun='%d'>"
            .formatted(result.status().detected(), xmlStatus(result.status()), result.testsRun()));
    element(out, "sourceFile", Path.of(sources.file(mutant)).getFileName().toString());
    element(out, "mutatedClass", mutant.binaryName());
    element(out, "mutatedMethod", mutant.methodName());
    element(out, "methodDescription", mutant.descriptor());
    element(out, "lineNumber", String.valueOf(sources.place(mutant).line()));
    element(out, "mutator", mutator(mutant));
    out.append("<indexes><index>").append(mutant.ordinal()).append("</index></indexes>");
    out.append("<blocks><block>0</block></blocks>");
    element(out, "killingTest", result.caughtBy().orElse(""));
    element(
        out,
        "succeedingTests",
        result.status() == Status.MISSED ? String.join("|", result.coveredBy()) : "");
    element(out, "description", sources.advice(mutant).change());
    out.append("</mutation>\n");
  }

  private static String xmlStatus(Status status) {
    return switch (status) {
      case CAUGHT -> "KILLED";
      case MISSED -> "SURVIVED";
      case TIMED_OUT -> "TIMED_OUT";
      default -> "NO_COVERAGE";
    };
  }

  /// The mutator's name as `mutations.xml` readers know it; an operator they don't know, by its own
  /// id.
  private static String mutator(Mutant mutant) {
    if (!(mutant.operator() instanceof io.mutastic.api.BuiltIn builtIn)) {
      return mutant.operator().id();
    }
    if (builtIn == io.mutastic.api.BuiltIn.LOGICAL_CONNECTOR) {
      return mutant.operator().id(); // readers know no name for it
    }
    var prefix = "org.pitest.mutationtest.engine.gregor.mutators.";
    return builtIn == io.mutastic.api.BuiltIn.COMPARISON
            || builtIn == io.mutastic.api.BuiltIn.OTHER_COMPARISON
        ? comparisonMutator(mutant, prefix)
        : prefix + knownMutator(builtIn, mutant);
  }

  /// Readers know a condition reversed; the other comparisons go by their id.
  private static String comparisonMutator(Mutant mutant, String prefix) {
    return Advice.reverses(mutant) ? prefix + "NegateConditionalsMutator" : mutant.operator().id();
  }

  private static String knownMutator(io.mutastic.api.BuiltIn builtIn, Mutant mutant) {
    return switch (builtIn) {
      case BOUNDARY -> "ConditionalsBoundaryMutator";
      case MATH, ARITHMETIC_REPLACEMENT, OTHER_ARITHMETIC -> "MathMutator";
      case CONSTANT_VALUE -> "InlineConstantMutator";
      case FLIP_INCREMENT -> "IncrementsMutator";
      case REMOVE_NEGATION -> "InvertNegsMutator";
      case REMOVE_CONDITIONAL -> "RemoveConditionalMutator";
      case REMOVE_VOID_CALL -> "VoidMethodCallMutator";
      case REMOVE_DISCARDED_CALL -> "NonVoidMethodCallMutator";
      case RETURN_VALUE -> returnsMutator(mutant.replacement());
      default -> mutant.operator().id(); // an operator with no established name goes by its own
    };
  }

  /// The value mutator `mutations.xml` readers know for the value a return now gives.
  private static String returnsMutator(String replacement) {
    var value = replacement.substring("return ".length());
    return "returns."
        + switch (value) {
          case "true" -> "BooleanTrueReturnValsMutator";
          case "false" -> "BooleanFalseReturnValsMutator";
          case "null" -> "NullReturnValsMutator";
          default ->
              Character.isDigit(value.charAt(0))
                  ? "PrimitiveReturnsMutator"
                  : "EmptyObjectReturnValsMutator";
        };
  }

  private static void element(StringBuilder out, String name, String value) {
    out.append('<')
        .append(name)
        .append('>')
        .append(escapeXml(value))
        .append("</")
        .append(name)
        .append('>');
  }

  static String escapeXml(String text) {
    return text.replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("'", "&apos;")
        .replace("\"", "&quot;");
  }

  private static String testClass(String test) {
    int hash = test.indexOf('#');
    return hash < 0 ? test : test.substring(0, hash);
  }

  private static String testName(String test) {
    int hash = test.indexOf('#');
    return hash < 0 ? test : test.substring(hash + 1);
  }

  private static String viewer() {
    try (InputStream in =
        ReportFiles.class.getResourceAsStream("viewer/mutation-test-elements.js")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
