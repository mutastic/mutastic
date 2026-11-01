package io.mutastic.report;

import io.mutastic.engine.MutantResult;
import io.mutastic.engine.Report;
import io.mutastic.engine.Status;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.Map;

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
    location.put("line", mutant.line());
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
