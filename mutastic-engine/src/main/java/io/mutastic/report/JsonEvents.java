package io.mutastic.report;

import io.mutastic.engine.Comparison;
import io.mutastic.engine.MutantResult;
import io.mutastic.engine.Report;
import io.mutastic.engine.Status;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// `--format jsonl`: one self-describing JSON object per line, written as each mutant finishes so
/// an editor or agent can follow the run. Statuses are the mutation-testing report schema's, so
/// the stream folds into tools that read that schema. Nothing else goes to stdout.
public final class JsonEvents {
  public static final String SCHEMA = "mutastic.events/v1";

  private final PrintStream out;
  private final SourceView sources;
  private final AgentText text;
  private final int threshold;

  public JsonEvents(PrintStream out, SourceView sources, int threshold) {
    this.out = out;
    this.sources = sources;
    this.text = new AgentText(sources, threshold);
    this.threshold = threshold;
  }

  public void runStarted(String project) {
    emit("run-started", Map.of("project", project));
  }

  public void mutantFinished(MutantResult result) {
    var mutant = result.mutant();
    var fields = new LinkedHashMap<String, Object>();
    fields.put("id", mutant.id());
    fields.put("fingerprint", mutant.fingerprint());
    fields.put("status", result.status().schemaName());
    fields.put("operator", mutant.operator().id());
    var location = new LinkedHashMap<String, Object>();
    location.put("file", sources.file(mutant));
    location.put("line", sources.place(mutant).line());
    location.put("class", mutant.binaryName());
    location.put("method", mutant.methodName() + mutant.descriptor());
    fields.put("location", location);
    var advice = Advice.of(mutant);
    var mutation = new LinkedHashMap<String, Object>();
    advice.before().ifPresent(before -> mutation.put("original", before));
    advice.after().ifPresent(after -> mutation.put("replacement", after));
    mutation.put("description", advice.change());
    mutation.put("bytecode", mutant.original() + " -> " + mutant.replacement());
    fields.put("mutation", mutation);
    mutant.skipped().ifPresent(reason -> {
      fields.put("statusReason", reason);
      fields.put("skipKind", reason.substring(0, reason.indexOf(':')));
    });
    fields.put("coveredBy", result.coveredBy());
    result.caughtBy().ifPresent(test -> fields.put("killedBy", test));
    result.failure().ifPresent(failure -> fields.put("killedWith", failure));
    result.failureOrigin().ifPresent(origin -> fields.put("failureOrigin", origin));
    result.unconfirmed().ifPresent(test -> fields.put("unconfirmedCatchBy", test));
    if (!result.inputs().isEmpty()) {
      fields.put("inputs", result.inputs());
    }
    SuggestedTest.of(result, sources).ifPresent(test -> {
      var suggestion = new LinkedHashMap<String, Object>();
      suggestion.put("testClass", test.testClass());
      suggestion.put("name", test.name());
      suggestion.put("code", String.join("\n", test.lines()));
      suggestion.put("call", result.suggestion().orElseThrow().call());
      suggestion.put("today", result.suggestion().orElseThrow().today());
      suggestion.put("withMutant", result.suggestion().orElseThrow().mutated());
      fields.put("suggestedTest", suggestion);
    });
    fields.put("testsRun", result.testsRun());
    fields.put("durationMs", result.took().toMillis());
    if (result.status() == Status.MISSED) {
      fields.put("reproduce", "mutastic run --only=" + mutant.id());
      fields.put("doneWhen", "mutastic verify %s passes; no production files changed".formatted(mutant.id()));
      fields.put("rendered", text.entry(0, result));
    }
    emit("mutant-finished", fields);
  }

  public void runFinished(Report report) {
    runFinished(report, Optional.empty());
  }

  public void runFinished(Report report, Optional<Comparison> comparison) {
    runFinished(report, comparison, List.of());
  }

  /// @param reports the report files written
  public void runFinished(Report report, Optional<Comparison> comparison, List<String> reports) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("jdk", report.jdk());
    fields.put("mutants", report.results().size());
    for (Status status : Status.values()) {
      fields.put(status.schemaName(), report.count(status));
    }
    fields.put("score", Math.round(report.score() * 10) / 10.0);
    fields.put("threshold", threshold);
    fields.put("passed", report.score() >= threshold);
    fields.put("tests", report.tests());
    fields.put("flaky", report.flaky());
    comparison.ifPresent(found -> {
      fields.put("previousScore", Math.round(found.previousScore() * 10) / 10.0);
      fields.put("sameCodeAsLastRun", found.sameCode());
      fields.put("sameMutasticAsLastRun", found.sameTool());
      fields.put("changedSinceLastRun", found.flips().stream().map(flip -> {
        var entry = new LinkedHashMap<String, Object>();
        entry.put("id", flip.id());
        entry.put("before", flip.before().schemaName());
        entry.put("after", flip.after().schemaName());
        flip.caughtBy().ifPresent(test -> entry.put("caughtBy", test));
        return entry;
      }).toList());
    });
    fields.put("likelyFlaky", Suspects.json(comparison, report));
    fields.put("unrelatedCatches", Suspects.unrelatedCatches(report).stream().map(note -> {
      var entry = new LinkedHashMap<String, Object>();
      entry.put("test", note.test());
      entry.put("failure", note.failure());
      entry.put("mutants", note.mutants());
      return entry;
    }).toList());
    fields.put("reports", reports);
    fields.put("durationMs", report.took().toMillis());
    emit("run-finished", fields);
  }

  private void emit(String reason, Map<String, Object> fields) {
    var event = new LinkedHashMap<String, Object>();
    event.put("schema", SCHEMA);
    event.put("reason", reason);
    event.putAll(fields);
    out.println(Json.write(event));
    out.flush();
  }
}
