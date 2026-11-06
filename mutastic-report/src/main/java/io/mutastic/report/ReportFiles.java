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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/// The run as files other tools read:
///
/// - `json`: the mutation-testing report schema (version 2), which report viewers, dashboards and
///   badges understand.
/// - `html`: that JSON in the schema's own viewer, embedded, so it opens offline from disk.
/// - `xml`: the `mutations.xml` format many build-server and code-quality plugins read.
public final class ReportFiles {
  public static final List<String> KINDS = List.of("json", "html", "xml");

  private final Path root;
  private final SourceView sources;
  private final int threshold;
  private final String version;

  /// `root` is the project directory: file paths in the reports are relative to it.
  public ReportFiles(Path root, SourceView sources, int threshold, String version) {
    this.root = root;
    this.sources = sources;
    this.threshold = threshold;
    this.version = version;
  }

  /// Writes the report of `kind` to `file`.
  public void write(String kind, Report report, Path file) {
    var text =
        switch (kind) {
          case "json" -> Json.write(json(report));
          case "html" -> html(report);
          case "xml" -> xml(report);
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
    return document;
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
      file.mutants().add(mutant(result));
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
              sources.testSource(testClass).ifPresent(source -> entry.put("source", source));
              entry.put("tests", testDefinitions(ids));
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

  private static List<Map<String, Object>> testDefinitions(List<String> ids) {
    return ids.stream()
        .distinct()
        .sorted()
        .map(id -> pair("id", id, "name", testName(id)))
        .toList();
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
      case COMPARISON,
          OTHER_COMPARISON,
          LOGICAL_CONNECTOR,
          REMOVE_ASSIGNMENT,
          REMOVE_FIELD_WRITE,
          OMIT_CALL,
          SWAP_ARGUMENTS,
          NULL_PARAMETER,
          DROP_VARARG ->
          mutant.operator().id();
      case MATH, ARITHMETIC_REPLACEMENT -> "MathMutator";
      case FLIP_INCREMENT -> "IncrementsMutator";
      case REMOVE_NEGATION -> "InvertNegsMutator";
      case REMOVE_CONDITIONAL -> "RemoveConditionalMutator";
      case REMOVE_VOID_CALL -> "VoidMethodCallMutator";
      case REMOVE_DISCARDED_CALL -> "NonVoidMethodCallMutator";
      case RETURN_VALUE -> returnsMutator(mutant.replacement());
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
