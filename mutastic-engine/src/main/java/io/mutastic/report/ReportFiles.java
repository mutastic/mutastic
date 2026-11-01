package io.mutastic.report;

import io.mutastic.engine.Mutant;
import io.mutastic.engine.MutantResult;
import io.mutastic.engine.Operator;
import io.mutastic.engine.Report;
import io.mutastic.engine.Status;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.lang.constant.MethodTypeDesc;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/// The run as files other tools read:
///
/// - `json`: the mutation-testing report schema (version 2), which report viewers, dashboards
///   and badges understand.
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
    var text = switch (kind) {
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

  /// The report-schema document. Statuses are the schema's (`Killed`, `Survived`, …);
  /// locations are 1-based with an exclusive end.
  Map<String, Object> json(Report report) {
    var files = new TreeMap<String, Map<String, Object>>();
    var tests = new TreeMap<String, List<String>>(); // test class → test ids
    for (MutantResult result : report.results()) {
      var mutant = result.mutant();
      var file = files.computeIfAbsent(sources.file(mutant), path -> {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("language", "java");
        entry.put("source", sources.text(mutant).orElse(""));
        entry.put("mutants", new java.util.ArrayList<Map<String, Object>>());
        return entry;
      });
      @SuppressWarnings("unchecked")
      var mutants = (List<Map<String, Object>>) file.get("mutants");
      mutants.add(mutant(result));
      result.coveredBy().forEach(test -> tests.computeIfAbsent(testClass(test), name -> new java.util.ArrayList<>()).add(test));
    }
    var testFiles = new TreeMap<String, Map<String, Object>>();
    tests.forEach((testClass, ids) -> {
      var definitions = ids.stream().distinct().sorted().map(id -> Map.<String, Object>of("id", id, "name", testName(id)))
          .toList();
      var entry = new LinkedHashMap<String, Object>();
      sources.testSource(testClass).ifPresent(source -> entry.put("source", source));
      entry.put("tests", definitions);
      testFiles.put(sources.testFile(testClass).orElse(testClass), entry);
    });
    var document = new LinkedHashMap<String, Object>();
    document.put("$schema", "https://raw.githubusercontent.com/stryker-mutator/mutation-testing-elements/master/packages/report-schema/src/mutation-testing-report-schema.json");
    document.put("schemaVersion", "2");
    // One threshold in mutastic: at or above it is good; the warning band is the 20 points below.
    document.put("thresholds", Map.of("high", threshold, "low", Math.max(0, threshold - 20)));
    document.put("projectRoot", root.toAbsolutePath().normalize().toString());
    document.put("files", files);
    document.put("testFiles", testFiles);
    document.put("framework", Map.of("name", "mutastic", "version", version));
    var performance = new LinkedHashMap<String, Object>();
    performance.put("setup", 0);
    performance.put("initialRun", report.coverage().toMillis());
    performance.put("mutation", report.took().minus(report.coverage()).toMillis());
    document.put("performance", performance);
    return document;
  }

  private Map<String, Object> mutant(MutantResult result) {
    var mutant = result.mutant();
    var advice = Advice.of(mutant);
    var columns = sources.columns(mutant, advice);
    var entry = new LinkedHashMap<String, Object>();
    entry.put("id", mutant.id());
    entry.put("mutatorName", mutant.operator().id().substring("mutastic/".length()));
    entry.put("replacement", advice.after().orElse(advice.change()));
    entry.put("description", advice.change());
    entry.put("location", Map.of(
        "start", Map.of("line", mutant.line(), "column", columns[0]),
        "end", Map.of("line", mutant.line(), "column", columns[1])));
    entry.put("status", result.status().schemaName());
    var reason = switch (result.status()) {
      case SKIPPED -> mutant.skipped().orElse("");
      case CAUGHT -> result.failure().orElse("");
      case MISSED -> advice.headline();
      case TIMED_OUT -> "the tests didn't finish in time";
      case NOT_COVERED -> "no test reaches this line";
    };
    if (!reason.isEmpty()) {
      entry.put("statusReason", reason);
    }
    entry.put("coveredBy", result.coveredBy());
    result.caughtBy().ifPresent(test -> entry.put("killedBy", List.of(test)));
    entry.put("testsCompleted", result.testsRun());
    entry.put("duration", result.took().toMillis());
    return entry;
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
        """.formatted(escapeXml(report.project()), viewer().replace("</script", "<\\/script"), data);
  }

  /// `mutations.xml`. Its consumers key on established mutator class names, so those are
  /// what `<mutator>` carries; skipped mutants aren't listed.
  String xml(Report report) {
    var out = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n<mutations partial=\"false\">\n");
    for (MutantResult result : report.results()) {
      if (result.status() == Status.SKIPPED) {
        continue;
      }
      var mutant = result.mutant();
      var status = switch (result.status()) {
        case CAUGHT -> "KILLED";
        case MISSED -> "SURVIVED";
        case TIMED_OUT -> "TIMED_OUT";
        default -> "NO_COVERAGE";
      };
      out.append("<mutation detected='%s' status='%s' numberOfTestsRun='%d'>".formatted(
          result.status().detected(), status, result.testsRun()));
      element(out, "sourceFile", Path.of(sources.file(mutant)).getFileName().toString());
      element(out, "mutatedClass", mutant.binaryName());
      element(out, "mutatedMethod", mutant.methodName());
      element(out, "methodDescription", mutant.descriptor());
      element(out, "lineNumber", String.valueOf(mutant.line()));
      element(out, "mutator", mutator(mutant));
      out.append("<indexes><index>").append(mutant.ordinal()).append("</index></indexes>");
      out.append("<blocks><block>0</block></blocks>");
      element(out, "killingTest", result.caughtBy().orElse(""));
      element(out, "succeedingTests", result.status() == Status.MISSED ? String.join("|", result.coveredBy()) : "");
      element(out, "description", Advice.of(mutant).change());
      out.append("</mutation>\n");
    }
    return out.append("</mutations>\n").toString();
  }

  private static String mutator(Mutant mutant) {
    var prefix = "org.pitest.mutationtest.engine.gregor.mutators.";
    return prefix + switch (mutant.operator()) {
      case BOUNDARY -> "ConditionalsBoundaryMutator";
      case NEGATE_CONDITIONAL -> "NegateConditionalsMutator";
      case MATH -> "MathMutator";
      case REMOVE_VOID_CALL -> "VoidMethodCallMutator";
      case RETURN_DEFAULT -> switch (MethodTypeDesc.ofDescriptor(mutant.descriptor()).returnType().descriptorString()) {
        case "Z" -> "returns.BooleanFalseReturnValsMutator";
        case "I", "J", "S", "B", "C", "F", "D" -> "returns.PrimitiveReturnsMutator";
        default -> "returns.NullReturnValsMutator";
      };
    };
  }

  private static void element(StringBuilder out, String name, String value) {
    out.append('<').append(name).append('>').append(escapeXml(value)).append("</").append(name).append('>');
  }

  static String escapeXml(String text) {
    return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("'", "&apos;").replace("\"", "&quot;");
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
    try (InputStream in = ReportFiles.class.getResourceAsStream("viewer/mutation-test-elements.js")) {
      return new String(in.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
