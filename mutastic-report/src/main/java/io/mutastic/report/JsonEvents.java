package io.mutastic.report;

import io.mutastic.api.Comparison;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;

/// `--format jsonl`: one self-describing JSON object per line, written as each mutant finishes so
/// an editor or agent can follow the run. Statuses are the mutation-testing report schema's, so the
/// stream folds into tools that read that schema. Nothing else goes to stdout.
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
    var fields = new LinkedHashMap<String, Object>();
    fields.put("project", project);
    fields.put("rules", MutantCommand.RULE);
    emit("run-started", fields);
  }

  public void mutantFinished(MutantResult result) {
    var mutant = result.mutant();
    var fields = new LinkedHashMap<String, Object>();
    fields.put("id", mutant.id());
    fields.put("fingerprint", mutant.fingerprint());
    fields.put("status", result.status().schemaName());
    fields.put("operator", mutant.operator().id());
    fields.put("location", location(mutant));
    fields.put("mutation", mutation(mutant));
    mutant.skipped().ifPresent(reason -> putSkipReason(fields, reason));
    fields.put("coveredBy", result.coveredBy());
    putCatch(fields, result);
    if (!result.inputs().isEmpty()) {
      fields.put("inputs", result.inputs());
    }
    SuggestedTest.of(result, sources)
        .ifPresent(test -> fields.put("suggestedTest", suggestedTest(test, result)));
    fields.put("testsRun", result.testsRun());
    fields.put("durationMs", result.took().toMillis());
    if (result.status() == Status.MISSED) {
      putMissedHelp(fields, result);
    }
    emit("mutant-finished", fields);
  }

  private Map<String, Object> location(io.mutastic.api.Mutant mutant) {
    var location = new LinkedHashMap<String, Object>();
    location.put("file", sources.file(mutant));
    location.put("line", sources.place(mutant).line());
    location.put("class", mutant.binaryName());
    location.put("method", mutant.methodName() + mutant.descriptor());
    return location;
  }

  private Map<String, Object> mutation(io.mutastic.api.Mutant mutant) {
    var advice = sources.advice(mutant);
    var mutation = new LinkedHashMap<String, Object>();
    advice.before().ifPresent(before -> mutation.put("original", before));
    advice.after().ifPresent(after -> mutation.put("replacement", after));
    mutation.put("description", advice.change());
    mutation.put("bytecode", mutant.original() + " -> " + mutant.replacement());
    return mutation;
  }

  /// Why it was skipped, and the kind of reason: its first word.
  private static void putSkipReason(Map<String, Object> fields, String reason) {
    fields.put("statusReason", reason);
    fields.put("skipKind", reason.substring(0, reason.indexOf(':')));
  }

  /// Which test caught it, with what, from where; or whose catch didn't repeat.
  private static void putCatch(Map<String, Object> fields, MutantResult result) {
    result.caughtBy().ifPresent(test -> fields.put("killedBy", test));
    result.failure().ifPresent(failure -> fields.put("killedWith", failure));
    result.failureOrigin().ifPresent(origin -> fields.put("failureOrigin", origin));
    result.unconfirmed().ifPresent(test -> fields.put("unconfirmedCatchBy", test));
  }

  private static Map<String, Object> suggestedTest(SuggestedTest test, MutantResult result) {
    var found = result.suggestion().orElseThrow();
    var suggestion = new LinkedHashMap<String, Object>();
    suggestion.put("testClass", test.testClass());
    suggestion.put("name", test.name());
    suggestion.put("code", String.join("\n", test.lines()));
    suggestion.put("call", found.call());
    suggestion.put("today", found.today());
    suggestion.put("withMutant", found.mutated());
    return suggestion;
  }

  /// The commands to work on a missed mutant, and its entry as the agent text has it.
  private void putMissedHelp(Map<String, Object> fields, MutantResult result) {
    for (MutantCommand command : MutantCommand.values()) {
      fields.put(command.key(), command.text(result.mutant().id(), sources.target()));
    }
    fields.put("rendered", text.entry(0, result));
  }

  public void runFinished(Report report) {
    runFinished(report, Optional.empty());
  }

  public void runFinished(Report report, Optional<Comparison> comparison) {
    runFinished(report, comparison, List.of(), false);
  }

  /// The last event: the totals, the score and verdict, and how this run compares with the last.
  ///
  /// @param reports the report files written
  /// @param oneMutant the run was one mutant asked for by id: `passed` says whether it's caught,
  ///   not whether a score reaches the threshold
  public void runFinished(
      Report report, Optional<Comparison> comparison, List<String> reports, boolean oneMutant) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("jdk", report.jdk());
    fields.put("mutants", report.results().size());
    for (Status status : Status.values()) {
      fields.put(status.schemaName(), report.count(status));
    }
    putVerdict(fields, report, oneMutant);
    fields.put("tests", report.tests());
    fields.put("flaky", report.flaky());
    comparison.ifPresent(found -> putComparison(fields, found));
    fields.put("likelyFlaky", Suspects.json(comparison, report));
    fields.put("unrelatedCatches", unrelatedCatches(report));
    fields.put("reports", reports);
    putAudit(fields, AridAudit.of(report));
    fields.put("durationMs", report.took().toMillis());
    emit("run-finished", fields);
  }

  /// An audit of the arid filters, when the run was one.
  private static void putAudit(Map<String, Object> fields, AridAudit audit) {
    if (audit.happened()) {
      var found = new LinkedHashMap<String, Object>();
      found.put("ran", audit.ran());
      found.put("caught", audit.caught());
      found.put("caughtByAssertion", audit.byAssertion());
      found.put("missed", audit.missed());
      found.put("notCovered", audit.notCovered());
      found.put("checkedIn", audit.caughtIn());
      fields.put("aridAudit", found);
    }
  }

  /// The score, the threshold, and whether the run passed.
  private void putVerdict(Map<String, Object> fields, Report report, boolean oneMutant) {
    fields.put("score", rounded(report.score()));
    fields.put("threshold", threshold);
    fields.put(
        "passed",
        oneMutant
            ? report.results().stream().allMatch(result -> result.status().detected())
            : report.passes(threshold));
    if (oneMutant) {
      fields.put(
          "only", report.results().isEmpty() ? null : report.results().getFirst().mutant().id());
    }
  }

  /// To one decimal place; null when there was nothing to score.
  private static @Nullable Double rounded(OptionalDouble score) {
    return score.isPresent() ? Math.round(score.getAsDouble() * 10) / 10.0 : null;
  }

  private static void putComparison(Map<String, Object> fields, Comparison found) {
    fields.put("previousScore", rounded(found.previousScore()));
    fields.put("sameCodeAsLastRun", found.sameCode());
    fields.put("sameMutasticAsLastRun", found.sameTool());
    fields.put("changedSinceLastRun", changedSinceLastRun(found));
  }

  private static List<Map<String, Object>> changedSinceLastRun(Comparison found) {
    return found.flips().stream()
        .<Map<String, Object>>map(
            flip -> {
              var entry = new LinkedHashMap<String, Object>();
              entry.put("id", flip.id());
              entry.put("before", flip.before().schemaName());
              entry.put("after", flip.after().schemaName());
              flip.caughtBy().ifPresent(test -> entry.put("caughtBy", test));
              return entry;
            })
        .toList();
  }

  private static List<Map<String, Object>> unrelatedCatches(Report report) {
    return Suspects.unrelatedCatches(report).stream()
        .<Map<String, Object>>map(
            note -> {
              var entry = new LinkedHashMap<String, Object>();
              entry.put("test", note.test());
              entry.put("failure", note.failure());
              entry.put("mutants", note.mutants());
              return entry;
            })
        .toList();
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
