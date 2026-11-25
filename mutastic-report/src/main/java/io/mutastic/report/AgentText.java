package io.mutastic.report;

import io.mutastic.api.Comparison;
import io.mutastic.api.FlakyTest;
import io.mutastic.api.LikelyFlaky;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import io.mutastic.api.WatchedTest;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Collectors;

/// `--format agent`: what a coding agent needs to close each gap, and nothing it would have to
/// skip. The verdict comes first, so a truncated read still has it; then each missed mutant with
/// the change, the evidence, the fix and how to prove it; then the lines no test reaches. No
/// colour, no progress, at most [#LIMIT] misses. Plain enough for people to read too.
public final class AgentText {
  static final int LIMIT = 10;
  static final int COVERING_SHOWN = 3;

  private final SourceView sources;
  private final int threshold;
  private final Vocabulary words;
  private final boolean eachMutant;
  private final Optional<FailOnNew> failOnNew;

  public AgentText(SourceView sources, int threshold) {
    this(sources, threshold, Vocabulary.PLAIN);
  }

  public AgentText(SourceView sources, int threshold, Vocabulary words) {
    this(sources, threshold, words, false, Optional.empty());
  }

  private AgentText(
      SourceView sources,
      int threshold,
      Vocabulary words,
      boolean eachMutant,
      Optional<FailOnNew> failOnNew) {
    this.sources = sources.shown();
    this.threshold = threshold;
    this.words = words;
    this.eachMutant = eachMutant;
    this.failOnNew = failOnNew;
  }

  /// The same, with an entry for every missed mutant, not one for each line's (`--each-mutant`).
  public AgentText eachMutant(boolean each) {
    return new AgentText(sources, threshold, words, each, failOnNew);
  }

  /// The same, passing a run on its misses the baseline doesn't hold, not on its score
  /// (`--fail-on-new`): the verdict line says so, and the new misses' entries come first.
  public AgentText failingOnNew(FailOnNew judged) {
    return new AgentText(sources, threshold, words, eachMutant, Optional.of(judged));
  }

  public String render(Report report) {
    return render(report, Optional.empty());
  }

  /// With `comparison`, the score against the last run and the tests whose catches changed.
  public String render(Report report, Optional<Comparison> comparison) {
    return render(report, comparison, false);
  }

  /// The whole run for an agent: the verdict, then each missed mutant, then what no test reaches.
  ///
  /// @param oneMutant the run was mutants asked for by id: each one's fate is the verdict, not a
  ///   score
  public String render(Report report, Optional<Comparison> comparison, boolean oneMutant) {
    return render(report, comparison, oneMutant, List.of());
  }

  /// The same, naming the report files the run wrote (see [#reports]).
  ///
  /// @param reports the files written, as the summary's Reports row has them
  public String render(
      Report report, Optional<Comparison> comparison, boolean oneMutant, List<Path> reports) {
    if (oneMutant && !report.results().isEmpty()) {
      return fates(report, reports);
    }
    var missed =
        report.results().stream().filter(result -> result.status() == Status.MISSED).toList();
    var notCovered =
        report.results().stream().filter(result -> result.status() == Status.NOT_COVERED).toList();
    var out = new StringBuilder();
    appendVerdict(out, report, missed.size(), notCovered.size());
    appendFlaky(out, report);
    appendStaleIgnores(out, report);
    RepeatedIgnores.in(report)
        .forEach(repeated -> out.append("note: %s\n".formatted(RepeatedIgnores.text(repeated))));
    comparison.ifPresent(found -> appendVsLastRun(out, report, found));
    out.append(reports(reports));
    appendSuspects(out, report);
    appendUnrelatedCatches(out, report);
    if (!missed.isEmpty() || !notCovered.isEmpty()) {
      appendGaps(out, report, missed, notCovered);
    }
    appendGhosts(out, report);
    return out.toString();
  }

  /// Each mutant asked for by id, its fate first; then the tests that fail on their own, and the
  /// reports written.
  private String fates(Report report, List<Path> reports) {
    return report.results().stream()
            .map(result -> oneMutant(report, result))
            .collect(Collectors.joining("\n"))
        + failingAlone(report)
        + reports(reports);
  }

  /// Ghosts, with `--experimental=ghosts`: questions about what the code should do, never part of
  /// the score or the gate, so an agent isn't asked to make them go away.
  private void appendGhosts(StringBuilder out, Report report) {
    if (!GhostCards.any(report)) {
      return;
    }
    out.append(
        ("\nghosts: %s. Each is a question for the person, not a gap to close: don't add the call"
                + " unless the task or spec says listeners should hear of that write.\n")
            .formatted(GhostCards.summary(report)));
    var cards = new GhostCards(sources);
    var quoted = new StringBuilder();
    GhostCards.found(report).stream()
        .filter(ghost -> !ghost.appeared())
        .forEach(ghost -> cards.card(ghost).forEach(line -> quoted.append(line).append('\n')));
    out.append(Untrusted.fence(quoted.toString()));
  }

  /// `reports: build/mutastic/mutation.json · build/mutastic/mutation.html`: the files the run
  /// wrote, as the summary's Reports row names them, each as typed from where mutastic ran with
  /// what can't be seen escaped. An agent that isn't told of them runs again to get one. Nothing
  /// when none was written (`--report=none`).
  static String reports(List<Path> written) {
    return written.isEmpty()
        ? ""
        : written.stream()
            .map(file -> Untrusted.escape(Here.shown(file)))
            .collect(Collectors.joining(" · ", "reports: ", "\n"));
  }

  /// `85.7%`, or `none` when there was nothing to score.
  private static String percent(OptionalDouble score) {
    return score.isPresent() ? "%.1f%%".formatted(score.getAsDouble()) : "none";
  }

  private static void appendVsLastRun(StringBuilder out, Report report, Comparison found) {
    if (found.notCompared().isPresent()) {
      // The last run's operators are read from its record, a file in the project's build.
      out.append("vs last run: %s\n".formatted(Untrusted.escape(found.notCompared().get())));
      return;
    }
    out.append(
        "vs last run: %s → %s (%s)\n"
            .formatted(percent(found.previousScore()), percent(report.score()), found.sameness()));
  }

  /// The rules, each missed mutant's entry, then the lines no test reaches.
  private void appendGaps(
      StringBuilder out, Report report, List<MutantResult> missed, List<MutantResult> notCovered) {
    out.append(
        "rules: %s; a fix counts when `%s` passes\n"
            .formatted(
                MutantCommand.RULE, MutantCommand.DONE_WHEN.command("<id>", sources.invocation())));
    out.append(
        "decline: %s (`%s`)\n"
            .formatted(
                MutantCommand.DECLINE,
                MutantCommand.PROPOSE.command("<id>", sources.invocation())));
    if (missed.size() + notCovered.size() > 1) {
      out.append(
          "tasks: %s\n"
              .formatted(tasks(MutantCommand.DONE_WHEN.command("<id>", sources.invocation()))));
    }
    // From here on, each line quotes the code or the test run: fenced, so none of it reads as
    // mutastic's own instructions.
    var quoted = new StringBuilder();
    appendMissed(quoted, missed);
    if (!notCovered.isEmpty()) {
      appendNotCovered(quoted, report, notCovered, missed.isEmpty());
    }
    out.append(Untrusted.fence(quoted.toString()));
  }

  /// With two or more mutants to write a test for, how an agent takes them: a task list, then one
  /// at a time, each proved before the next. The Rust edition and the HTML report's Copy as prompt
  /// say the same; `verify` is the run's own `mutastic verify` for `<id>`.
  static String tasks(String verify) {
    return ("first make a task list with one item per mutant, grouped by file; then take them one at"
            + " a time: write its test, run `%s`, and mark it done before starting the next")
        .formatted(verify);
  }

  /// The one mutant's fate, and for a missed one the rules and its entry.
  private String oneMutant(Report report, MutantResult result) {
    var out = new StringBuilder();
    out.append("mutastic: %s %s\n".formatted(result.mutant().id(), fate(report, result)));
    if (result.status() == Status.MISSED) {
      out.append(
          "rules: %s; a fix counts when `%s` passes\n"
              .formatted(
                  MutantCommand.RULE,
                  MutantCommand.DONE_WHEN.command(result.mutant().id(), sources.invocation())));
      out.append(Untrusted.fence("\n" + entry(0, result)));
    }
    return out.toString();
  }

  private String fate(Report report, MutantResult result) {
    return switch (result.status()) {
      case CAUGHT ->
          "is %s by %s%s"
              .formatted(
                  words.detected,
                  Untrusted.value(result.caughtBy().orElse("a test")),
                  evidence(result));
      case TIMED_OUT -> "is %s: its tests stopped finishing in time".formatted(words.detected);
      case MISSED -> "is still %s".formatted(words.undetected);
      case NOT_COVERED ->
          onlyLikelyFlaky(report, result)
              .map(only -> "isn't covered: " + only)
              .orElse("isn't reached by any test");
      case SKIPPED -> "is skipped: " + Untrusted.value(result.mutant().skipped().orElse(""));
    };
  }

  /// `mutastic: FAIL · score …`: the verdict line, first so a truncated read still has it.
  private void appendVerdict(StringBuilder out, Report report, int missed, int notCovered) {
    var skipped = report.count(Status.SKIPPED);
    out.append(
        "mutastic: %s · score %s (%s%s) · %d %s, %d %s, %d %s of %d mutant%s%s\n"
            .formatted(
                verdict(report),
                report.score().isPresent() ? percent(report.score()) : "none (no mutants to score)",
                failOnNew.isPresent() ? "threshold not applied" : "threshold " + threshold,
                notCovered == 0 || report.coveredScore().isEmpty()
                    ? ""
                    : "; %s where tests reach".formatted(percent(report.coveredScore())),
                missed,
                words.undetected,
                notCovered,
                words.notCovered,
                report.count(Status.CAUGHT) + report.count(Status.TIMED_OUT),
                words.detected,
                report.scored(),
                report.scored() == 1 ? "" : "s",
                skipped == 0 ? "" : " (%d more skipped)".formatted(skipped)));
  }

  /// `PASS`, `FAIL`, or why it failed when the score isn't why; with `--fail-on-new`, the new
  /// misses that decided it.
  private String verdict(Report report) {
    if (!report.failingAlone().isEmpty()) {
      return "FAIL (a test that caught mutants fails on its own, with no mutant in)";
    }
    if (failOnNew.isPresent()) {
      return failOnNew.get().verdict(report);
    }
    return report.passes(threshold) ? "PASS" : "FAIL";
  }

  /// A line for each test that caught mutants and fails on its own, saying what to do: for `verify`
  /// and the gate, as for a run.
  public static String failingAlone(Report report) {
    return report.failingAlone().stream()
        .map(AgentText::failingAloneLine)
        .collect(Collectors.joining());
  }

  /// What an agent does about a test that can't pass here: never leave it out itself, as a test
  /// left out may be a broken one hidden, but tell the person, or propose it to them.
  static final String PROPOSE_LEAVING_OUT =
      "tell the person, or propose leaving it out under `exclude-tests:` in mutastic.yaml with its"
          + " reason, for them to decide";

  /// A test that caught mutants, then failed on its own with no mutant in, run again after them: it
  /// passed among the others, so it counts on what something else sets up or leaves behind. Never
  /// the way past it, leaving it out: that's the person's call.
  private static String failingAloneLine(FlakyTest why) {
    return ("flaky: %s passes among the others but fails on its own, with no mutant in (run again"
            + " after the mutants, it failed%s): it counts on something another test or the run"
            + " sets up or leaves behind. It's left out, so its catches don't count: the mutants it"
            + " reached ran again without it, and the run can't pass until it passes on its own."
            + " Make it set up what it needs and clean up what it leaves. If it can't be made to"
            + " pass on its own now, don't leave it out yourself: %s. Never remove, disable or"
            + " loosen it to get a run through\n")
        .formatted(
            shortName(why.test()),
            why.failedWith().map(failed -> " with " + Untrusted.value(failed)).orElse(""),
            PROPOSE_LEAVING_OUT);
  }

  /// Decisions the code has outgrown: say so, since the mutants they covered ran.
  private void appendStaleIgnores(StringBuilder out, Report report) {
    if (!report.staleIgnores().isEmpty()) {
      out.append(
          "stale ignores: %s no longer match their code, so their mutants ran; ask before removing them (%s)\n"
              .formatted(
                  Untrusted.values(
                      report.staleIgnores().stream().map(io.mutastic.api.Ignore::id).toList()),
                  sources.invocation().command(HumanText.PRUNE)));
    }
  }

  /// Each flaky test, and what to do about it by what mutastic saw: a test that's its own doing (an
  /// unseeded `Random`, the clock) can be made to answer the same every run, but one that failed on
  /// the network or a database would only be hidden by changing it. "Fix it first" told an agent to
  /// do the second as readily as the first.
  private static void appendFlaky(StringBuilder out, Report report) {
    for (var test : report.flaky()) {
      var why = report.whyFlaky(test);
      out.append(
          why.filter(FlakyTest::failsAlone).isPresent()
              ? failingAloneLine(why.get())
              : "flaky: %s failed, then passed when run again; %s. %s Never remove, disable or loosen it to get a run through\n"
                  .formatted(whatFailed(test), leftOut(test), flakyAdvice(test, why)));
    }
  }

  /// What the evidence says to do about a flaky test, in this order: the test's own code varies
  /// (its doing, even if it fails on the network, as a stand-in with a random delay does); it fails
  /// from outside with nothing of its own varying (the environment's doing); the code it runs
  /// varies; or nothing is known.
  static String flakyAdvice(String test, Optional<FlakyTest> why) {
    if (why.isPresent() && !why.get().reachesInTest().isEmpty()) {
      return ownDoing(why.get());
    }
    var outside = why.flatMap(FlakyTest::outsideCause);
    if (why.isPresent() && why.get().failedOutside() && outside.isPresent()) {
      return ("It failed with %s, from outside the code (the network, a database, a file), and"
              + " nothing in the test varies: that's the environment, and changing the test or the"
              + " code to get past it would hide it, so leave both and %s.")
          .formatted(Untrusted.value(outside.get()), PROPOSE_LEAVING_OUT);
    }
    if (why.isPresent() && !why.get().reaches().isEmpty()) {
      return ("The code it runs reaches %s, which answers differently every run: making that the"
              + " same every time (a seed, a fixed clock, through the code's own seam) is likely"
              + " safe, so long as the test still checks what it did.")
          .formatted(Untrusted.values(why.get().reaches()));
    }
    return "mutastic can't tell why%s: leave it unless the cause is plain in the code, and say in your final message that it's flaky."
        .formatted(
            why.flatMap(FlakyTest::failedWith)
                .map(failed -> " (it failed with %s)".formatted(Untrusted.value(failed)))
                .orElse(""));
  }

  /// A test whose own code varies: making it the same every run is its fix.
  private static String ownDoing(FlakyTest why) {
    return ("Its own code reaches %s, which answers differently every run, so it makes its own"
            + " flakiness%s: making that the same every time is likely safe, and if it stands in"
            + " for something real (a slow service), keep that checked by a test of its own.")
        .formatted(
            Untrusted.values(why.reachesInTest()),
            why.outsideCause()
                .map(failed -> " (it failed with %s)".formatted(Untrusted.value(failed)))
                .orElse(""));
  }

  /// The tests whose catches can't be trusted, strongest signal first, each saying that none of its
  /// catches count; then each test watched since an earlier run named it.
  private static void appendSuspects(StringBuilder out, Report report) {
    for (var named : report.likelyFlaky()) {
      var changed = named.mutants(LikelyFlaky.CHANGED);
      if (!changed.isEmpty()) {
        out.append(
            ("likely flaky: %s; with the code unchanged, whether it catches %s changed since the last"
                    + " run. Its catches can't be trusted until it's fixed. %s\n")
                .formatted(shortName(named.test()), Untrusted.values(changed), noneCount(named)));
      }
      var unconfirmed = named.mutants(LikelyFlaky.NOT_REPEATED);
      if (!unconfirmed.isEmpty()) {
        out.append(
            "likely flaky: %s; its %s of %s didn't repeat when run again, so the other covering tests decided. %s\n"
                .formatted(
                    shortName(named.test()),
                    unconfirmed.size() == 1 ? "catch" : "catches",
                    Untrusted.values(unconfirmed),
                    noneCount(named)));
      }
    }
    for (var watched : report.watched()) {
      out.append("watched: %s\n".formatted(watchedLine(watched)));
    }
  }

  /// `None of its catches count: 3 mutants it reached ran again without it, and the next runs
  /// confirm each catch it makes 3 times.`
  static String noneCount(LikelyFlaky named) {
    int rerun = named.rerun().size();
    return ("None of its catches count: %d %s it reached ran again without it, and the next runs"
            + " confirm each catch it makes %d times.")
        .formatted(rerun, rerun == 1 ? "mutant" : "mutants", WatchedTest.CONFIRMATIONS);
  }

  /// `PricingTest#bigOrders, likely flaky in an earlier run: each catch it made had to repeat 3
  /// times; 1 of 3 runs held`, and `…, so it's no longer watched` once it has held enough.
  static String watchedLine(WatchedTest watched) {
    return "%s, likely flaky in an earlier run: each catch it made had to repeat %d times; %d of %d runs held%s"
        .formatted(
            shortName(watched.test()),
            WatchedTest.CONFIRMATIONS,
            watched.held(),
            WatchedTest.NEEDED,
            watched.released() ? ", so it's no longer watched" : "");
  }

  private static void appendUnrelatedCatches(StringBuilder out, Report report) {
    for (var note : Suspects.unrelatedCatches(report)) {
      out.append(
          ("note: %s caught %d mutants in %d different methods, each with %s thrown in the test itself; "
                  + "if it fails that way whatever changes, it may be flaky\n")
              .formatted(
                  shortName(note.test()),
                  note.mutants().size(),
                  note.methods(),
                  Untrusted.value(note.failure().substring(note.failure().lastIndexOf('.') + 1))));
    }
  }

  /// The first [#LIMIT] findings' entries, and how many more missed mutants there are.
  private void appendMissed(StringBuilder out, List<MutantResult> missed) {
    var findings = newFirst(Finding.ranked(Finding.of(missed, eachMutant)));
    int number = 0;
    for (Finding finding : findings.stream().limit(LIMIT).toList()) {
      out.append('\n').append(entry(++number, finding));
    }
    if (findings.size() > LIMIT) {
      out.append(
          "\n%d more %s mutants: run again after fixing these\n"
              .formatted(
                  Finding.mutantsIn(findings.subList(LIMIT, findings.size())), words.undetected));
    }
  }

  /// With `--fail-on-new`, the findings with a new miss first and the baseline's after, each in the
  /// run's order, as `next` queues them; else as they are.
  private List<Finding> newFirst(List<Finding> ranked) {
    if (failOnNew.isEmpty()) {
      return ranked;
    }
    var baseline = failOnNew.get().baseline();
    var ordered = new ArrayList<Finding>();
    ranked.stream().filter(finding -> !Queue.isLegacy(finding, baseline)).forEach(ordered::add);
    ranked.stream().filter(finding -> Queue.isLegacy(finding, baseline)).forEach(ordered::add);
    return ordered;
  }

  private void appendNotCovered(
      StringBuilder out, Report report, List<MutantResult> notCovered, boolean noneMissed) {
    out.append(
        "\n%s (no test reaches these lines%s%s):\n"
            .formatted(
                words.notCovered,
                anyFlaky(report) ? ", or only a flaky one does" : "",
                noneMissed ? "" : "; fix the %s mutants first".formatted(words.undetected)));
    notCoveredByLine(notCovered)
        .forEach(
            (where, mutants) -> {
              int count = mutants.size();
              out.append("- %s (%d mutant%s)\n".formatted(where, count, count == 1 ? "" : "s"));
              onlyLikelyFlaky(report, mutants)
                  .ifPresent(only -> out.append("  %s\n".formatted(only)));
              OnlyFromMain.advice(mutants, sources)
                  .ifPresent(advice -> out.append("  %s\n".formatted(advice)));
              appendProposals(out, mutants);
            });
    SuggestedTest.startingPoints(notCovered, sources, LIMIT)
        .forEach(
            assertion -> out.append("  start with: %s\n".formatted(Untrusted.value(assertion))));
  }

  /// `src/…/Pricing.java:12-15 discount()` to the mutants no test reaches there, in report order:
  /// by method, or with `--each-mutant` by line.
  private Map<String, List<io.mutastic.api.Mutant>> notCoveredByLine(
      List<MutantResult> notCovered) {
    return NotCovered.grouped(
        notCovered, sources, eachMutant, mutant -> Advice.method(mutant).replace("`", ""));
  }

  /// How to propose ignoring each of a place's mutants, by line: an agent decides a line no test
  /// reaches can't be reached as it decides a missed mutant's case can't happen.
  private void appendProposals(StringBuilder out, List<io.mutastic.api.Mutant> mutants) {
    var byLine = new java.util.TreeMap<Integer, List<String>>();
    mutants.forEach(
        mutant ->
            byLine
                .computeIfAbsent(sources.place(mutant).line(), line -> new ArrayList<>())
                .add(mutant.id()));
    byLine.forEach(
        (line, ids) ->
            out.append(
                "  %s%s: %s\n"
                    .formatted(
                        MutantCommand.PROPOSE.label(),
                        byLine.size() == 1 ? "" : " (line %d)".formatted(line),
                        MutantCommand.PROPOSE.eachOf(ids, sources.invocation()))));
  }

  /// One missed mutant, numbered, or unnumbered (0) where it stands alone.
  public String entry(int number, MutantResult result) {
    return entry(number, new Finding(List.of(result)));
  }

  /// One finding: its lead mutant in full, the others on its line each by its change, one fix, each
  /// distinct test to add, and the commands for them all.
  String entry(int number, Finding finding) {
    var result = finding.lead();
    var mutant = result.mutant();
    var advice = sources.advice(result);
    var lines = new ArrayList<String>();
    lines.add(head(number, mutant, advice));
    lines.add("   at %s:%d".formatted(sources.file(mutant), sources.place(mutant).line()));
    addChange(lines, sources.diff(mutant, advice));
    finding.others().forEach(other -> addOther(lines, other));
    lines.add(coveredBy(result));
    advice.notes().forEach(note -> lines.add("   evidence: " + note));
    addFix(lines, finding, advice);
    for (MutantCommand command : MutantCommand.toAnAgent()) {
      lines.add(
          "   %s: %s".formatted(command.label(), command.text(finding, sources.invocation())));
    }
    return String.join("\n", lines) + "\n";
  }

  /// `1. missed m855c8e3c: headline`, unnumbered for 0.
  private String head(int number, io.mutastic.api.Mutant mutant, Advice advice) {
    return "%s%s %s: %s"
        .formatted(
            number == 0 ? "" : number + ". ", words.undetected, mutant.id(), advice.headline());
  }

  /// `also missed m2e491c40: headline`, then its line as it changes.
  private void addOther(List<String> lines, MutantResult other) {
    lines.add(
        "   also %s %s: %s"
            .formatted(words.undetected, other.mutant().id(), sources.advice(other).headline()));
    var diff = sources.diff(other.mutant(), sources.advice(other));
    if (diff.shown() && !diff.removed()) {
      diff.shownAfter().forEach(line -> lines.add("   + " + line));
    } else {
      lines.add("   change: " + (diff.removed() ? diff.change() : diff.unshown()));
    }
  }

  /// `- the statement` and `+ the statement as the mutant has it`, or the change in words where the
  /// source can't show it: never words where code goes. A statement the mutant takes out has no
  /// `+`.
  private static void addChange(List<String> lines, Diff diff) {
    diff.shownBefore().forEach(line -> lines.add("   - " + line));
    if (diff.shown()) {
      diff.shownAfter().forEach(line -> lines.add("   + " + line));
    } else {
      lines.add("   change: " + diff.unshown());
    }
  }

  private static String coveredBy(MutantResult result) {
    return "   %s; all still pass with the change%s%s%s"
        .formatted(
            testsRun(result, ": "),
            result
                .unconfirmed()
                .map(test -> " (%s failed once, then passed: flaky)".formatted(shortName(test)))
                .orElse(""),
            result.falseCatchBy().map(test -> " (%s)".formatted(falseCatch(test))).orElse(""),
            result.flakyCatchBy().map(test -> " (%s)".formatted(flakyCatch(test))).orElse(""));
  }

  /// For a mutant that only tests named likely flaky reached, which were left out of it: `only
  /// PricingTest#a reached it, and it's likely flaky: its catch didn't count`, or `… so it was left
  /// out` for one that had reached it without catching it. Empty for any other mutant, so a person
  /// isn't sent to write a test that already exists.
  public static Optional<String> onlyLikelyFlaky(Report report, MutantResult result) {
    var id = result.mutant().id();
    var tests = report.likelyFlakyReaching(id);
    if (result.status() != Status.NOT_COVERED || tests.isEmpty()) {
      return Optional.empty();
    }
    boolean caught = caughtByOneOf(report, result, tests);
    boolean one = tests.size() == 1;
    return Optional.of(
        "only %s reached it, and %s likely flaky: %s"
            .formatted(
                String.join(" and ", shortNames(tests)),
                one ? "it's" : "they're",
                caught
                    ? (one ? "its catch didn't count" : "their catches didn't count")
                    : (one ? "it was left out" : "they were left out")));
  }

  /// Whether one of `tests` had caught the mutant before its catch was taken away.
  private static boolean caughtByOneOf(Report report, MutantResult result, List<String> tests) {
    var id = result.mutant().id();
    return tests.stream()
        .anyMatch(
            test ->
                report.likelyFlaky(test).filter(named -> named.mutants().contains(id)).isPresent()
                    || result.flakyCatchBy().filter(test::equals).isPresent()
                    || result.falseCatchBy().filter(test::equals).isPresent());
  }

  /// For a line's mutants, when the first is one only tests named likely flaky reached: what
  /// [#onlyLikelyFlaky(Report, MutantResult)] says of it.
  static Optional<String> onlyLikelyFlaky(Report report, List<Mutant> mutants) {
    return report.results().stream()
        .filter(result -> result.mutant().id().equals(mutants.getFirst().id()))
        .findFirst()
        .flatMap(result -> onlyLikelyFlaky(report, result));
  }

  /// Whether a test the run left out as flaky, or named likely flaky, may be all that reached a
  /// mutant it doesn't cover.
  static boolean anyFlaky(Report report) {
    return !report.flaky().isEmpty() || !report.likelyFlaky().isEmpty();
  }

  /// What a mutant's card says of the likely flaky test whose catch of it didn't count.
  static String flakyCatch(String test) {
    return "%s failed against it, but it's likely flaky: that catch doesn't count"
        .formatted(shortName(test));
  }

  /// What a mutant's card says of the test whose catch of it was false.
  static String falseCatch(String test) {
    return "%s failed against it, but fails on its own with no mutant in too: that catch doesn't count"
        .formatted(shortName(test));
  }

  /// Which tests a mutant ran against: `covered by: A, B`, or every test, for a change coverage
  /// can't place, where most of them reach nothing of it.
  static String testsRun(MutantResult result, String colon) {
    return ranEveryTest(result)
        ? "run against every test"
        : "covered by" + colon + covering(result.coveredBy());
  }

  static boolean ranEveryTest(MutantResult result) {
    return result.mutant().operator() instanceof io.mutastic.api.ClassMutator mutator
        && mutator.reach() == io.mutastic.api.ClassMutator.Reach.EVERY_TEST;
  }

  /// `fix: the fix`, then each test to add; when the entry has a test for more than one of its
  /// mutants, each after its own mutant's fix, `fix for m2e491c40: …`.
  private void addFix(List<String> lines, Finding finding, Advice advice) {
    var each = SuggestedTest.eachMutant(finding, sources);
    if (!SuggestedTest.eachHasItsOwn(each)) {
      lines.add("   fix: " + advice.fix());
      SuggestedTest.distinct(finding, sources).forEach(test -> addSuggestedTest(lines, test));
      return;
    }
    lines.add("   fix: " + SuggestedTest.eachHelp(each));
    for (SuggestedTest.ForMutant one : each) {
      lines.add(
          "   fix for %s: %s"
              .formatted(one.result().mutant().id(), sources.advice(one.result()).fix()));
      one.test().ifPresent(test -> addSuggestedTest(lines, test));
    }
  }

  private static void addSuggestedTest(List<String> lines, SuggestedTest test) {
    lines.add(
        "   test to add in %s:"
            .formatted(Untrusted.value(SourceNames.sourceName(test.testClass()))));
    test.lines().forEach(line -> lines.add("     " + Untrusted.value(line)));
    lines.add(
        "     // check today's value is the one you intend: the test pins what the code does now");
  }

  /// `PricingTest#bigOrders` for `com.example.PricingTest#bigOrders`. The test that failed, or a
  /// class that failed as a whole: `FlakySetupTest as a whole (its setup or teardown, outside any
  /// test)`.
  static String whatFailed(String test) {
    return test.contains("#")
        ? shortName(test)
        : shortName(test) + " as a whole (its setup or teardown, outside any test)";
  }

  /// What leaving it out means: for a class, every test in it.
  static String leftOut(String test) {
    return test.contains("#")
        ? "left out of this run, so its catches don't count"
        : "left out of this run with every test in it, so their catches don't count";
  }

  /// `PricingTest#bigOrders`, as it's shown: [Untrusted#value]d, as a test's name is the project's.
  /// ` (case [2] 2, 3, 5)`, the case of the catching test that failed, when it runs a table of
  /// them: evidence of this run, after the test it's a case of.
  public static String inCase(MutantResult result) {
    return result
        .caughtCase()
        .map(failed -> " (case " + Untrusted.value(failed.name()) + ")")
        .orElse("");
  }

  /// What a line names after the catching test, as evidence of this run: the case of it that failed
  /// ([#inCase]), then `` with `Arg 0: 18` `` when it's a property test that names the input it
  /// failed on, the input [Untrusted#value]d, as it's the project's.
  public static String evidence(MutantResult result) {
    return inCase(result)
        + result.caughtInput().map(input -> " with `" + Untrusted.value(input) + "`").orElse("");
  }

  static String shortName(String test) {
    int hash = test.indexOf('#');
    var className = hash < 0 ? test : test.substring(0, hash);
    return Untrusted.value(
        className.substring(className.lastIndexOf('.') + 1)
            + (hash < 0 ? "" : test.substring(hash)));
  }

  static List<String> shortNames(List<String> tests) {
    return tests.stream().map(AgentText::shortName).toList();
  }

  /// `total = 50, 250; quantity = 3`: what the covering tests passed in.
  static String inputs(java.util.Map<String, List<Long>> inputs) {
    return inputs.entrySet().stream()
        .map(
            entry ->
                entry.getKey()
                    + " = "
                    + entry.getValue().stream()
                        .map(String::valueOf)
                        .collect(Collectors.joining(", ")))
        .collect(Collectors.joining("; "));
  }

  /// The first few, the mutant's own test class's first, and how many more there are.
  static String covering(List<String> tests) {
    var shown =
        tests.stream()
            .limit(COVERING_SHOWN)
            .map(AgentText::shortName)
            .collect(Collectors.joining(", "));
    return tests.size() <= COVERING_SHOWN
        ? shown
        : "%s and %d more".formatted(shown, tests.size() - COVERING_SHOWN);
  }
}
