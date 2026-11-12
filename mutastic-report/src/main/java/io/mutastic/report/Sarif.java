package io.mutastic.report;

import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Operator;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/// The run as SARIF 2.1.0, which code scanning reads: GitHub's alerts on a pull request's lines,
/// Azure DevOps, and SARIF viewers in an editor.
///
/// One rule per operator that made a result, and one result per missed or not-covered mutant: a
/// missed one a warning, as a test runs the code and doesn't check it, a not-covered one a note.
/// Each result's `partialFingerprints` carries the mutant's id, which stays the same when code
/// above it moves, so an alert follows its mutant. A result never has a `fixes` entry: the fix is a
/// test, which the card suggests, never a change to the code.
///
/// Paths are relative to the git repository the project is in, as code scanning resolves them
/// against the repository's root, or to the project where there's no repository.
final class Sarif {
  static final String SCHEMA = "https://json.schemastore.org/sarif-2.1.0.json";

  /// The key the mutant's id goes under in `partialFingerprints`.
  static final String FINGERPRINT = "mutasticId/v1";

  private final Path root;
  private final SourceView sources;
  private final String version;

  /// `root` is the project's directory, which the sources' paths are relative to.
  Sarif(Path root, SourceView sources, String version) {
    this.root = root;
    this.sources = sources;
    this.version = version;
  }

  Map<String, Object> document(Report report) {
    var flagged =
        report.results().stream()
            .filter(
                result -> result.status() == Status.MISSED || result.status() == Status.NOT_COVERED)
            .toList();
    var rules = rules(flagged);
    var ruleIds = new ArrayList<>(rules.keySet());
    var results = new ArrayList<Map<String, Object>>();
    for (MutantResult result : flagged) {
      results.add(result(result, ruleIds.indexOf(result.mutant().operator().id())));
    }
    var document = new LinkedHashMap<String, Object>();
    document.put("$schema", SCHEMA);
    document.put("version", "2.1.0");
    document.put("runs", List.of(run(rules, results)));
    return document;
  }

  /// The one run: mutastic and its rules, where the paths are from, and the results.
  private Map<String, Object> run(
      Map<String, Map<String, Object>> rules, List<Map<String, Object>> results) {
    var driver = new LinkedHashMap<String, Object>();
    driver.put("name", "mutastic");
    driver.put("version", version);
    driver.put("informationUri", "https://docs.mutastic.io");
    driver.put("rules", new ArrayList<>(rules.values()));
    var run = new LinkedHashMap<String, Object>();
    run.put("tool", Map.of("driver", driver));
    var base = repository().orElse(root.toAbsolutePath().normalize());
    run.put("originalUriBaseIds", Map.of("%SRCROOT%", Map.of("uri", base.toUri().toString())));
    run.put("results", results);
    return run;
  }

  /// Each operator that made a result, by its id, in order.
  private static Map<String, Map<String, Object>> rules(List<MutantResult> flagged) {
    var rules = new TreeMap<String, Map<String, Object>>();
    for (MutantResult result : flagged) {
      rules.computeIfAbsent(
          result.mutant().operator().id(), id -> rule(result.mutant().operator()));
    }
    return rules;
  }

  private static Map<String, Object> rule(Operator operator) {
    var rule = new LinkedHashMap<String, Object>();
    rule.put("id", operator.id());
    rule.put("shortDescription", text(firstSentence(operator.explanation())));
    rule.put("fullDescription", text(operator.explanation()));
    rule.put("help", text(operator.explanation()));
    rule.put("defaultConfiguration", Map.of("level", "warning"));
    rule.put("properties", Map.of("tags", List.of("mutation-testing", "tests")));
    return rule;
  }

  private Map<String, Object> result(MutantResult result, int ruleIndex) {
    var mutant = result.mutant();
    var advice = sources.advice(mutant);
    var entry = new LinkedHashMap<String, Object>();
    entry.put("ruleId", mutant.operator().id());
    entry.put("ruleIndex", ruleIndex);
    entry.put("level", result.status() == Status.MISSED ? "warning" : "note");
    entry.put("message", message(advice));
    entry.put("locations", List.of(Map.of("physicalLocation", physicalLocation(mutant))));
    entry.put("partialFingerprints", Map.of(FINGERPRINT, mutant.id()));
    var properties = new LinkedHashMap<String, Object>();
    properties.put("id", mutant.id());
    properties.put("status", result.status().schemaName());
    properties.put("change", advice.change());
    entry.put("properties", properties);
    return entry;
  }

  /// The card's headline: plain as text, with its code marked as Markdown, which viewers render.
  private static Map<String, Object> message(Advice advice) {
    var message = new LinkedHashMap<String, Object>();
    message.put("text", advice.headline().replace("`", ""));
    message.put("markdown", advice.headline());
    return message;
  }

  /// Where the mutant is: its file, from the base, and the line and columns of its change.
  private Map<String, Object> physicalLocation(Mutant mutant) {
    var place = sources.place(mutant);
    var region = new LinkedHashMap<String, Object>();
    region.put("startLine", place.line());
    region.put("startColumn", place.start());
    region.put("endColumn", place.end());
    var artifact = new LinkedHashMap<String, Object>();
    artifact.put("uri", fromBase(sources.file(mutant)));
    artifact.put("uriBaseId", "%SRCROOT%");
    var physical = new LinkedHashMap<String, Object>();
    physical.put("artifactLocation", artifact);
    physical.put("region", region);
    return physical;
  }

  /// `src/main/java/…`, relative to the project, as it's relative to the base: the repository's
  /// root, where the project may be a folder down.
  private String fromBase(String fromProject) {
    var project = root.toAbsolutePath().normalize();
    return repository()
        .map(repository -> repository.relativize(project.resolve(fromProject)).toString())
        .orElse(fromProject)
        .replace('\\', '/');
  }

  /// The git repository the project is in: the nearest directory, from the project up, with a
  /// `.git` in it.
  private Optional<Path> repository() {
    for (var at = root.toAbsolutePath().normalize(); at != null; at = at.getParent()) {
      if (Files.exists(at.resolve(".git"))) {
        return Optional.of(at);
      }
    }
    return Optional.empty();
  }

  /// The text up to the first full stop that ends a sentence: a rule's one-line description.
  private static String firstSentence(String text) {
    var end = text.indexOf(". ");
    return end < 0 ? text : text.substring(0, end + 1);
  }

  private static Map<String, Object> text(String text) {
    return Map.of("text", text);
  }
}
