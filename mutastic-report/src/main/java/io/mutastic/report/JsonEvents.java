package io.mutastic.report;

import io.mutastic.api.ChangedLines;
import io.mutastic.api.Comparison;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.io.PrintStream;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/// `--format jsonl`: one self-describing JSON object per line, written as each mutant finishes so
/// an editor or agent can follow the run. Statuses are the mutation-testing report schema's, so the
/// stream folds into tools that read that schema. Nothing else goes to stdout.
public final class JsonEvents {
  public static final String SCHEMA = "mutastic.events/v1";

  /// Which mutastic writes the events: this one, for the JVM. Rust's writes `rust`.
  static final String EDITION = "java";

  private final PrintStream out;
  private final SourceView sources;
  private final AgentText text;
  private final int threshold;
  private final Optional<Share> share;

  public JsonEvents(PrintStream out, SourceView sources, int threshold) {
    this(out, sources, threshold, Optional.empty());
  }

  /// The events of one share of a run split with `--shard`, when `share` is there: `run-started`
  /// says which, so the shares can be put back together as the whole run.
  public JsonEvents(PrintStream out, SourceView sources, int threshold, Optional<Share> share) {
    this.out = out;
    this.sources = sources;
    this.text = new AgentText(sources, threshold);
    this.threshold = threshold;
    this.share = share;
  }

  /// Which share of a split run this is, and what it ran on: the shares of one run are of the same
  /// count, mutastic and code.
  ///
  /// @param number which share, from 1
  /// @param of how many shares the run's mutants were dealt into
  /// @param version the mutastic that ran it
  /// @param code the hash of the module's compiled code and tests
  public record Share(int number, int of, String version, String code) {}

  public void runStarted(String project) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("project", project);
    fields.put("rule", MutantCommand.RULE);
    fields.put("decline", MutantCommand.DECLINE);
    share.ifPresent(found -> fields.put("share", shareFields(found)));
    emit("run-started", fields);
  }

  private static Map<String, Object> shareFields(Share share) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("number", share.number());
    fields.put("of", share.of());
    fields.put("version", share.version());
    fields.put("code", share.code());
    return fields;
  }

  public void mutantFinished(MutantResult result) {
    emit("mutant-finished", mutantFields(result));
  }

  /// A mutant's result again, without a test that turned out to fail on its own: for every mutant
  /// that test reached, caught or missed, since either result may rest on it. `falseCatchBy` names
  /// the test only when it was the one that caught the mutant. It replaces the mutant's
  /// `mutant-finished`.
  public void mutantRevised(MutantResult result) {
    emit("mutant-revised", mutantFields(result));
  }

  private Map<String, Object> mutantFields(MutantResult result) {
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
    putSuggestion(fields, result);
    fields.put("testsRun", result.testsRun());
    fields.put("durationMs", result.took().toMillis());
    if (result.status() == Status.MISSED) {
      putMissedHelp(fields, result);
    }
    return fields;
  }

  private Map<String, Object> location(Mutant mutant) {
    var location = new LinkedHashMap<String, Object>();
    location.put("file", sources.file(mutant));
    location.put("line", sources.place(mutant).line());
    location.put("class", mutant.binaryName());
    location.put("method", mutant.methodName() + mutant.descriptor());
    return location;
  }

  private Map<String, Object> mutation(Mutant mutant) {
    var advice = sources.advice(mutant);
    var mutation = new LinkedHashMap<String, Object>();
    advice.before().ifPresent(before -> mutation.put("original", before));
    advice.after().ifPresent(after -> mutation.put("replacement", after));
    mutation.put("description", advice.change());
    var applied = new LinkedHashMap<String, Object>();
    applied.put("level", "bytecode");
    applied.put("text", mutant.original() + " -> " + mutant.replacement());
    mutation.put("applied", applied);
    return mutation;
  }

  /// The kinds `skipKind` may be, as the events schema lists them.
  private static final Set<String> SKIP_KINDS =
      Set.of("generated", "equivalent", "unviable", "arid", "user");

  /// Why it was skipped, and the kind of reason, its first word, when that's one of the kinds: a
  /// plugin's reason may have none, and then `statusReason` says it all.
  private static void putSkipReason(Map<String, Object> fields, String reason) {
    fields.put("statusReason", reason);
    var colon = reason.indexOf(':');
    if (colon > 0 && SKIP_KINDS.contains(reason.substring(0, colon))) {
      fields.put("skipKind", reason.substring(0, colon));
    }
  }

  /// Which test caught it, with what, from where; or whose catch didn't repeat.
  private static void putCatch(Map<String, Object> fields, MutantResult result) {
    result.caughtBy().ifPresent(test -> fields.put("caughtBy", test));
    result.failure().ifPresent(failure -> fields.put("caughtWith", failure));
    if (!result.caughtByAll().isEmpty()) {
      fields.put("caughtByAll", result.caughtByAll());
    }
    result.failureOrigin().ifPresent(origin -> fields.put("failureOrigin", origin));
    result.unconfirmed().ifPresent(test -> fields.put("unconfirmedCatchBy", test));
    result.falseCatchBy().ifPresent(test -> fields.put("falseCatchBy", test));
  }

  /// The test to write, when one was found, and the call it was found by, whole: what a share's
  /// events need for the card to be written again from them.
  private void putSuggestion(Map<String, Object> fields, MutantResult result) {
    SuggestedTest.of(result, sources)
        .ifPresent(test -> fields.put("suggestedTest", suggestedTest(test, result)));
    result.suggestion().ifPresent(found -> fields.put("suggestion", suggestion(found)));
  }

  private static Map<String, Object> suggestion(io.mutastic.api.Suggestion found) {
    var suggestion = new LinkedHashMap<String, Object>();
    suggestion.put("call", found.call());
    suggestion.put("parameters", found.parameters());
    suggestion.put("arguments", found.inputs());
    suggestion.put("receiver", found.receiver().stream().map(JsonEvents::instance).toList());
    suggestion.put("today", found.today());
    suggestion.put("withMutant", found.mutated());
    found.named().ifPresent(named -> suggestion.put("named", namedValue(named)));
    found.builtBy().ifPresent(test -> suggestion.put("builtBy", test));
    return suggestion;
  }

  private static Map<String, Object> namedValue(io.mutastic.api.Suggestion.Named named) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("name", named.name());
    fields.put("note", named.note());
    named.local().ifPresent(local -> fields.put("local", local));
    if (!named.meaning().isEmpty()) {
      fields.put("meaning", named.meaning());
    }
    return fields;
  }

  private static Map<String, Object> instance(io.mutastic.api.Suggestion.Instance instance) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("type", instance.type());
    fields.put("arguments", instance.arguments());
    fields.put("parameters", instance.parameters());
    return fields;
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
      if (command != MutantCommand.EXPLAIN) {
        fields.put(command.key(), command.text(result.mutant().id(), sources.invocation()));
      }
    }
    fields.put("rendered", text.entry(0, result));
  }

  public void runFinished(Report report) {
    runFinished(report, Optional.empty());
  }

  /// `mutastic explain`'s last event: what the text's About this mutant says, a field for each of
  /// its rows, in the same words. After the run's own events for the mutant, so the card's facts
  /// are its `mutant-finished`.
  ///
  /// @param lastRun how the last full run went for it; the field is left out when there was none
  public void mutantExplained(Mutant mutant, Optional<Explanation.LastRun> lastRun) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("id", mutant.id());
    fields.put("bytecode", bytecode(mutant));
    var operator = new LinkedHashMap<String, Object>();
    operator.put("id", mutant.operator().id());
    operator.put("description", mutant.operator().explanation());
    fields.put("operator", operator);
    fields.put("fingerprint", mutant.fingerprint());
    var id = Explanation.id(mutant);
    fields.put("idDescription", id.getFirst());
    fields.put("idNote", id.getLast());
    fields.put("site", site(mutant));
    lastRun.ifPresent(found -> fields.put("lastRun", lastRun(found)));
    emit("mutant-explained", fields);
  }

  /// The opcodes before and after, what each does, and how the jump relates to the source.
  private Map<String, Object> bytecode(Mutant mutant) {
    var bytecode = new LinkedHashMap<String, Object>();
    bytecode.put("original", mutant.original());
    bytecode.put("replacement", mutant.replacement());
    bytecode.put("meanings", Explanation.meanings(mutant));
    new Explanation(sources, Style.PLAIN)
        .jump(mutant)
        .ifPresent(note -> bytecode.put("note", note));
    return bytecode;
  }

  /// Which site of its method it is: counted from 1, but for a change to the class as a whole.
  private static Map<String, Object> site(Mutant mutant) {
    var site = new LinkedHashMap<String, Object>();
    if (!(mutant.operator() instanceof io.mutastic.api.ClassMutator)) {
      site.put("ordinal", mutant.ordinal() + 1);
    }
    site.put("method", mutant.methodName() + mutant.descriptor());
    site.put("description", Explanation.site(mutant));
    return site;
  }

  private static Map<String, Object> lastRun(Explanation.LastRun run) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("status", run.status().schemaName());
    run.caughtBy().ifPresent(test -> fields.put("caughtBy", test));
    fields.put("description", Explanation.described(run));
    return fields;
  }

  /// `mutastic next`'s answer: a `next-item` for each item shown, its lead mutant as
  /// `mutant-finished` has it, with every id on its line and where it stands and why; then a
  /// `next-finished` saying how many are queued.
  public void next(Queue queue, int count) {
    var shown = queue.top(count);
    for (int at = 0; at < shown.size(); at++) {
      var finding = shown.get(at);
      var fields = mutantFields(finding.lead());
      fields.put("ids", finding.mutants().stream().map(result -> result.mutant().id()).toList());
      fields.put("rank", rank(queue, finding, at + 1));
      emit("next-item", fields);
    }
    var finished = new LinkedHashMap<String, Object>();
    finished.put("queued", queue.findings().size());
    finished.put("mutants", queue.mutants());
    finished.put("shown", shown.size());
    finished.put("legacy", queue.legacyCount());
    finished.put("waiting", queue.waiting());
    emit("next-finished", finished);
  }

  private static Map<String, Object> rank(Queue queue, Finding finding, int position) {
    var rank = new LinkedHashMap<String, Object>();
    rank.put("position", position);
    rank.put("order", Queue.ORDER);
    rank.put("factors", queue.factors(finding).stream().map(JsonEvents::factor).toList());
    return rank;
  }

  private static Map<String, Object> factor(Queue.Factor factor) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("signal", factor.signal());
    fields.put("value", factor.value());
    fields.put("why", factor.why());
    return fields;
  }

  public void runFinished(Report report, Optional<Comparison> comparison) {
    runFinished(report, comparison, List.of(), false);
  }

  /// The last event: the totals, the score and verdict, and how this run compares with the last.
  ///
  /// @param reports the report files written
  /// @param oneMutant the run was mutants asked for by id: `passed` says whether each is caught,
  ///   not whether a score reaches the threshold
  public void runFinished(
      Report report, Optional<Comparison> comparison, List<String> reports, boolean oneMutant) {
    var fields = new LinkedHashMap<String, Object>();
    fields.put("jdk", report.jdk());
    fields.put("toolchain", toolchain(report));
    fields.put("mutants", report.results().size());
    putCounts(fields, report);
    putVerdict(fields, report, oneMutant);
    putTests(fields, report);
    fields.put("flaky", report.flaky());
    fields.put("flakyEvidence", flakyEvidence(report));
    comparison.ifPresent(found -> putComparison(fields, found));
    fields.put("likelyFlaky", Suspects.json(comparison, report));
    fields.put("unrelatedCatches", unrelatedCatches(report));
    fields.put("reports", reports);
    putAudit(fields, AridAudit.of(report));
    if (GhostCards.lookedFor(report)) {
      fields.put("ghosts", ghosts(report));
    }
    report.scope().ifPresent(scope -> fields.put("scope", scopeOf(scope)));
    fields.put(
        "staleIgnores", report.staleIgnores().stream().map(io.mutastic.api.Ignore::id).toList());
    fields.put("durationMs", report.took().toMillis());
    emit("run-finished", fields);
  }

  /// How many tests, and how the run went about them: how many workers side by side, how many
  /// started in all (one more after each kill), and how long the coverage pass took.
  private static void putTests(Map<String, Object> fields, Report report) {
    fields.put("tests", report.tests());
    fields.put("workers", report.workers());
    fields.put("workersStarted", report.started());
    fields.put("coverageMs", report.coverage().toMillis());
  }

  /// The change a run was scoped to: what it's since, and how much it touched. Each flaky test's
  /// failure, outermost first, what it reaches that changes from run to run, and whether the
  /// failure came from outside the code.
  private static List<Map<String, Object>> flakyEvidence(Report report) {
    var all = new java.util.ArrayList<Map<String, Object>>();
    for (var evidence : report.flakyEvidence()) {
      var fields = new LinkedHashMap<String, Object>();
      fields.put("test", evidence.test());
      fields.put("failure", evidence.failure());
      fields.put("reaches", evidence.reaches());
      fields.put("reachesInTest", evidence.reachesInTest());
      // A flag: there only when true, as the schema says, so a reader checks for it.
      if (evidence.failsAlone()) {
        fields.put("failsAlone", true);
      }
      fields.put("outside", evidence.failedOutside());
      all.add(fields);
    }
    return all;
  }

  /// What compiled and ran the code, in the words every edition shares: `jdk` and its release.
  private static Map<String, Object> toolchain(Report report) {
    var toolchain = new LinkedHashMap<String, Object>();
    toolchain.put("name", "jdk");
    toolchain.put("version", String.valueOf(report.jdk()));
    return toolchain;
  }

  private static Map<String, Object> scopeOf(ChangedLines scope) {
    var found = new LinkedHashMap<String, Object>();
    found.put("since", scope.since());
    found.put("fileCount", scope.files());
    found.put("lineCount", scope.lineCount());
    return found;
  }

  /// Each ghost, with `--experimental=ghosts`: experimental, and never in the score or the counts.
  /// `appeared` when a test noticed the inserted call, `passedUnseen` when none did, `notCovered`
  /// when no test runs the write.
  private List<Map<String, Object>> ghosts(Report report) {
    var cards = new GhostCards(sources);
    return GhostCards.found(report).stream().map(ghost -> ghost(ghost, cards)).toList();
  }

  private Map<String, Object> ghost(io.mutastic.api.Ghost ghost, GhostCards cards) {
    var result = ghost.result();
    var mutant = result.mutant();
    var fields = new LinkedHashMap<String, Object>();
    fields.put("id", mutant.id());
    fields.put("experimental", true);
    fields.put("status", GhostCards.status(ghost));
    fields.put("file", sources.file(mutant));
    fields.put("line", mutant.line());
    fields.put("class", mutant.className().replace('/', '.'));
    fields.put("method", mutant.methodName());
    fields.put("field", ghost.field());
    fields.put("inserted", mutant.replacement());
    fields.put(
        "rule",
        ghost.rule() == io.mutastic.api.Ghost.Rule.SAME_FIELD ? "sameField" : "siblingFields");
    fields.put("writes", ghost.writes());
    fields.put("peers", peers(ghost));
    fields.put("coveredBy", result.coveredBy());
    result.caughtBy().ifPresent(test -> fields.put("caughtBy", test));
    fields.put("rendered", String.join("\n", cards.card(ghost)));
    return fields;
  }

  private static List<Map<String, Object>> peers(io.mutastic.api.Ghost ghost) {
    return ghost.peers().stream()
        .<Map<String, Object>>map(
            peer -> {
              var fields = new LinkedHashMap<String, Object>();
              fields.put("method", peer.method());
              fields.put("line", peer.line());
              fields.put("property", peer.property());
              return fields;
            })
        .toList();
  }

  /// How many mutants have each status, keyed as every other field is (`killed`).
  private static void putCounts(Map<String, Object> fields, Report report) {
    for (Status status : Status.values()) {
      fields.put(countKey(status), report.count(status));
    }
  }

  /// `noCoverage`: a status's count, keyed as every other field is.
  private static String countKey(Status status) {
    var name = status.schemaName();
    return Character.toLowerCase(name.charAt(0)) + name.substring(1);
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
    // The score of the code some test reaches: how well the tests check what they run.
    fields.put("coveredScore", rounded(report.coveredScore()));
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
    event.put("edition", EDITION);
    event.put("reason", reason);
    event.putAll(fields);
    out.print(Json.write(event) + "\n");
    out.flush();
  }
}
