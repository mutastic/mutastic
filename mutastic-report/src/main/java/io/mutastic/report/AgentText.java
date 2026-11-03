package io.mutastic.report;

import io.mutastic.api.Comparison;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
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

  public AgentText(SourceView sources, int threshold) {
    this(sources, threshold, Vocabulary.PLAIN);
  }

  public AgentText(SourceView sources, int threshold, Vocabulary words) {
    this(sources, threshold, words, false);
  }

  private AgentText(SourceView sources, int threshold, Vocabulary words, boolean eachMutant) {
    this.sources = sources;
    this.threshold = threshold;
    this.words = words;
    this.eachMutant = eachMutant;
  }

  /// The same, with an entry for every missed mutant, not one for each line's (`--each-mutant`).
  public AgentText eachMutant(boolean each) {
    return new AgentText(sources, threshold, words, each);
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
  /// @param oneMutant the run was one mutant asked for by id: its fate is the verdict, not a score
  public String render(Report report, Optional<Comparison> comparison, boolean oneMutant) {
    if (oneMutant && !report.results().isEmpty()) {
      return oneMutant(report.results().getFirst());
    }
    var missed =
        report.results().stream().filter(result -> result.status() == Status.MISSED).toList();
    var notCovered =
        report.results().stream().filter(result -> result.status() == Status.NOT_COVERED).toList();
    var out = new StringBuilder();
    appendVerdict(out, report, missed.size(), notCovered.size());
    appendFlaky(out, report);
    appendStaleIgnores(out, report);
    comparison.ifPresent(found -> appendVsLastRun(out, report, found));
    appendSuspects(out, report, comparison);
    appendUnrelatedCatches(out, report);
    if (!missed.isEmpty() || !notCovered.isEmpty()) {
      appendGaps(out, report, missed, notCovered);
    }
    return out.toString();
  }

  /// `85.7%`, or `none` when there was nothing to score.
  private static String percent(OptionalDouble score) {
    return score.isPresent() ? "%.1f%%".formatted(score.getAsDouble()) : "none";
  }

  private static void appendVsLastRun(StringBuilder out, Report report, Comparison found) {
    out.append(
        "vs last run: %s → %s (%s)%n"
            .formatted(percent(found.previousScore()), percent(report.score()), found.sameness()));
  }

  /// The rules, each missed mutant's entry, then the lines no test reaches.
  private void appendGaps(
      StringBuilder out, Report report, List<MutantResult> missed, List<MutantResult> notCovered) {
    out.append(
        "rules: %s; a fix counts when `%s`%n"
            .formatted(
                MutantCommand.RULE, MutantCommand.DONE_WHEN.text("<id>", sources.invocation())));
    appendMissed(out, missed);
    if (!notCovered.isEmpty()) {
      appendNotCovered(out, report, notCovered, missed.isEmpty());
    }
  }

  /// The one mutant's fate, and for a missed one the rules and its entry.
  private String oneMutant(MutantResult result) {
    var out = new StringBuilder();
    out.append("mutastic: %s %s%n".formatted(result.mutant().id(), fate(result)));
    if (result.status() == Status.MISSED) {
      out.append(
          "rules: %s; a fix counts when `%s`%n%n"
              .formatted(
                  MutantCommand.RULE,
                  MutantCommand.DONE_WHEN.text(result.mutant().id(), sources.invocation())));
      out.append(entry(0, result));
    }
    return out.toString();
  }

  private String fate(MutantResult result) {
    return switch (result.status()) {
      case CAUGHT -> "is %s by %s".formatted(words.detected, result.caughtBy().orElse("a test"));
      case TIMED_OUT -> "is %s: its tests stopped finishing in time".formatted(words.detected);
      case MISSED -> "is still %s".formatted(words.undetected);
      case NOT_COVERED -> "isn't reached by any test";
      case SKIPPED -> "is skipped: " + result.mutant().skipped().orElse("");
    };
  }

  /// `mutastic: FAIL · score …`: the verdict line, first so a truncated read still has it.
  private void appendVerdict(StringBuilder out, Report report, int missed, int notCovered) {
    var skipped = report.count(Status.SKIPPED);
    out.append(
        "mutastic: %s · score %s (threshold %d%s) · %d %s, %d %s, %d %s of %d mutants%s%n"
            .formatted(
                report.passes(threshold) ? "PASS" : "FAIL",
                report.score().isPresent() ? percent(report.score()) : "none (no mutants to score)",
                threshold,
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
                skipped == 0 ? "" : " (%d more skipped)".formatted(skipped)));
  }

  /// Decisions the code has outgrown: say so, since the mutants they covered ran.
  private void appendStaleIgnores(StringBuilder out, Report report) {
    if (!report.staleIgnores().isEmpty()) {
      out.append(
          "stale ignores: %s no longer match their code, so their mutants ran; ask before removing them (%s)%n"
              .formatted(
                  String.join(
                      ", ",
                      report.staleIgnores().stream().map(io.mutastic.api.Ignore::id).toList()),
                  sources.invocation().command(HumanText.PRUNE)));
    }
  }

  private static void appendFlaky(StringBuilder out, Report report) {
    if (!report.flaky().isEmpty()) {
      out.append(
          "flaky: %s failed, then passed when run again; left out of this run, so %s catches don't count. "
                  .formatted(
                      String.join(", ", shortNames(report.flaky())),
                      report.flaky().size() == 1 ? "its" : "their")
              + "Fix %s first: %s on something that changes from run to run%n"
                  .formatted(
                      report.flaky().size() == 1 ? "it" : "them",
                      report.flaky().size() == 1 ? "it depends" : "they depend"));
    }
  }

  /// The tests whose catches can't be trusted, strongest signal first.
  private static void appendSuspects(
      StringBuilder out, Report report, Optional<Comparison> comparison) {
    for (var suspect : Suspects.changedBetweenRuns(comparison)) {
      out.append(
          "likely flaky: %s; with the code unchanged, whether it catches %s changed since the last run. "
                  .formatted(shortName(suspect.test()), String.join(", ", suspect.mutants()))
              + "Its catches can't be trusted until it's fixed%n".formatted());
    }
    for (var suspect : Suspects.unconfirmed(report)) {
      out.append(
          "likely flaky: %s; its %s of %s didn't repeat when run again, so the other covering tests decided%n"
              .formatted(
                  shortName(suspect.test()),
                  suspect.mutants().size() == 1 ? "catch" : "catches",
                  String.join(", ", suspect.mutants())));
    }
  }

  private static void appendUnrelatedCatches(StringBuilder out, Report report) {
    for (var note : Suspects.unrelatedCatches(report)) {
      out.append(
          ("note: %s caught %d mutants in %d different methods, each with %s thrown in the test itself; "
                  + "if it fails that way whatever changes, it may be flaky%n")
              .formatted(
                  shortName(note.test()),
                  note.mutants().size(),
                  note.methods(),
                  note.failure().substring(note.failure().lastIndexOf('.') + 1)));
    }
  }

  /// The first [#LIMIT] findings' entries, and how many more missed mutants there are.
  private void appendMissed(StringBuilder out, List<MutantResult> missed) {
    var findings = Finding.ranked(Finding.of(missed, eachMutant));
    int number = 0;
    for (Finding finding : findings.stream().limit(LIMIT).toList()) {
      out.append(System.lineSeparator()).append(entry(++number, finding));
    }
    if (findings.size() > LIMIT) {
      out.append(
          "%n%d more %s mutants: run again after fixing these%n"
              .formatted(
                  Finding.mutantsIn(findings.subList(LIMIT, findings.size())), words.undetected));
    }
  }

  private void appendNotCovered(
      StringBuilder out, Report report, List<MutantResult> notCovered, boolean noneMissed) {
    out.append(
        "%n%s (no test reaches these lines%s%s):%n"
            .formatted(
                words.notCovered,
                report.flaky().isEmpty() ? "" : ", or only a flaky one does",
                noneMissed ? "" : "; fix the %s mutants first".formatted(words.undetected)));
    notCoveredByLine(notCovered)
        .forEach(
            (where, count) ->
                out.append("- %s (%d mutant%s)%n".formatted(where, count, count == 1 ? "" : "s")));
    SuggestedTest.startingPoints(notCovered, sources, LIMIT)
        .forEach(assertion -> out.append("  start with: %s%n".formatted(assertion)));
  }

  /// `src/…/Pricing.java:12-15 discount()` to how many mutants no test reaches there, in report
  /// order: by method, or with `--each-mutant` by line.
  private Map<String, Long> notCoveredByLine(List<MutantResult> notCovered) {
    return NotCovered.counted(
        notCovered, sources, eachMutant, mutant -> Advice.method(mutant).replace("`", ""));
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
    sources.line(mutant).ifPresent(line -> lines.add("   - " + line));
    lines.add("   + " + mutated(result));
    finding.others().forEach(other -> addOther(lines, other));
    lines.add(coveredBy(result));
    advice.notes().forEach(note -> lines.add("   evidence: " + note));
    lines.add("   fix: " + advice.fix());
    SuggestedTest.distinct(finding, sources).forEach(test -> addSuggestedTest(lines, test));
    for (MutantCommand command : MutantCommand.withAMutant()) {
      lines.add(
          "   %s: %s".formatted(command.label(), command.text(finding, sources.invocation())));
    }
    return String.join(System.lineSeparator(), lines) + System.lineSeparator();
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
    lines.add("   + " + mutated(other));
  }

  private String mutated(MutantResult result) {
    var advice = sources.advice(result);
    return sources.mutatedLine(result.mutant(), advice).orElse("(" + advice.change() + ")");
  }

  private static String coveredBy(MutantResult result) {
    return "   covered by: %s; all still pass with the change%s"
        .formatted(
            covering(result.coveredBy()),
            result
                .unconfirmed()
                .map(test -> " (%s failed once, then passed: flaky)".formatted(shortName(test)))
                .orElse(""));
  }

  private static void addSuggestedTest(List<String> lines, SuggestedTest test) {
    lines.add("   test to add in %s:".formatted(SourceNames.sourceName(test.testClass())));
    test.lines().forEach(line -> lines.add("     " + line));
    lines.add(
        "     // check today's value is the one you intend: the test pins what the code does now");
  }

  /// `PricingTest#bigOrders` for `com.example.PricingTest#bigOrders`.
  static String shortName(String test) {
    int hash = test.indexOf('#');
    var className = hash < 0 ? test : test.substring(0, hash);
    return className.substring(className.lastIndexOf('.') + 1)
        + (hash < 0 ? "" : test.substring(hash));
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

  /// The fastest few, which are the ones to read first, and how many more there are.
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
