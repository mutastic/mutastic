package io.mutastic.report;

import io.mutastic.engine.Comparison;
import io.mutastic.engine.MutantResult;
import io.mutastic.engine.Report;
import io.mutastic.engine.Status;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
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

  public AgentText(SourceView sources, int threshold) {
    this(sources, threshold, Vocabulary.PLAIN);
  }

  public AgentText(SourceView sources, int threshold, Vocabulary words) {
    this.sources = sources;
    this.threshold = threshold;
    this.words = words;
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
    var out = new StringBuilder();
    if (oneMutant && !report.results().isEmpty()) {
      var result = report.results().getFirst();
      out.append(
          "mutastic: %s %s%n"
              .formatted(
                  result.mutant().id(),
                  switch (result.status()) {
                    case CAUGHT ->
                        "is %s by %s".formatted(words.detected, result.caughtBy().orElse("a test"));
                    case TIMED_OUT ->
                        "is %s: its tests stopped finishing in time".formatted(words.detected);
                    case MISSED -> "is still %s".formatted(words.undetected);
                    case NOT_COVERED -> "isn't reached by any test";
                    case SKIPPED -> "is skipped: " + result.mutant().skipped().orElse("");
                  }));
      if (result.status() == Status.MISSED) {
        out.append(
            "rules: %s; a fix counts when `%s`%n%n"
                .formatted(
                    MutantCommand.RULE,
                    MutantCommand.DONE_WHEN.text(result.mutant().id(), sources.target())));
        out.append(entry(0, result));
      }
      return out.toString();
    }
    var missed =
        report.results().stream().filter(result -> result.status() == Status.MISSED).toList();
    var notCovered =
        report.results().stream().filter(result -> result.status() == Status.NOT_COVERED).toList();
    var skipped = report.count(Status.SKIPPED);
    out.append(
        "mutastic: %s · score %.1f%% (threshold %d) · %d %s, %d %s, %d %s of %d mutants%s%n"
            .formatted(
                report.score() >= threshold ? "PASS" : "FAIL",
                report.score(),
                threshold,
                missed.size(),
                words.undetected,
                notCovered.size(),
                words.notCovered,
                report.count(Status.CAUGHT) + report.count(Status.TIMED_OUT),
                words.detected,
                report.scored(),
                skipped == 0 ? "" : " (%d more skipped)".formatted(skipped)));
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
    comparison.ifPresent(
        found ->
            out.append(
                "vs last run: %.1f%% → %.1f%% (%s)%n"
                    .formatted(found.previousScore(), report.score(), found.sameness())));
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
    if (missed.isEmpty() && notCovered.isEmpty()) {
      return out.toString();
    }
    out.append(
        "rules: %s; a fix counts when `%s`%n"
            .formatted(MutantCommand.RULE, MutantCommand.DONE_WHEN.text("<id>", sources.target())));
    int number = 0;
    for (MutantResult result : missed.stream().limit(LIMIT).toList()) {
      out.append(System.lineSeparator()).append(entry(++number, result));
    }
    if (missed.size() > LIMIT) {
      out.append(
          "%n%d more %s mutants: run again after fixing these%n"
              .formatted(missed.size() - LIMIT, words.undetected));
    }
    if (!notCovered.isEmpty()) {
      out.append(
          "%n%s (no test reaches these lines%s%s):%n"
              .formatted(
                  words.notCovered,
                  report.flaky().isEmpty() ? "" : ", or only a flaky one does",
                  missed.isEmpty()
                      ? ""
                      : "; fix the %s mutants first".formatted(words.undetected)));
      var byLine = new LinkedHashMap<String, Long>();
      notCovered.forEach(
          result ->
              byLine.merge(
                  "%s:%d %s"
                      .formatted(
                          sources.file(result.mutant()),
                          sources.place(result.mutant()).line(),
                          Advice.method(result.mutant()).replace("`", "")),
                  1L,
                  Long::sum));
      byLine.forEach(
          (where, count) ->
              out.append("- %s (%d mutant%s)%n".formatted(where, count, count == 1 ? "" : "s")));
    }
    return out.toString();
  }

  /// One missed mutant, numbered, or unnumbered (0) where it stands alone.
  public String entry(int number, MutantResult result) {
    var mutant = result.mutant();
    var advice = sources.advice(result);
    var lines = new ArrayList<String>();
    lines.add(
        "%s%s %s: %s"
            .formatted(
                number == 0 ? "" : number + ". ",
                words.undetected,
                mutant.id(),
                advice.headline()));
    lines.add("   at %s:%d".formatted(sources.file(mutant), sources.place(mutant).line()));
    sources.line(mutant).ifPresent(line -> lines.add("   - " + line));
    lines.add("   + " + sources.mutatedLine(mutant, advice).orElse("(" + advice.change() + ")"));
    lines.add(
        "   covered by: %s; all still pass with the change%s"
            .formatted(
                covering(result.coveredBy()),
                result
                    .unconfirmed()
                    .map(test -> " (%s failed once, then passed: flaky)".formatted(shortName(test)))
                    .orElse("")));
    advice.notes().forEach(note -> lines.add("   evidence: " + note));
    lines.add("   fix: " + advice.fix());
    SuggestedTest.of(result, sources)
        .ifPresent(
            test -> {
              lines.add("   test to add in %s:".formatted(test.testClass()));
              test.lines().forEach(line -> lines.add("     " + line));
              lines.add(
                  "     // check today's value is the one you intend: the test pins what the code does now");
            });
    for (MutantCommand command : MutantCommand.values()) {
      lines.add(
          "   %s: %s".formatted(command.label(), command.text(mutant.id(), sources.target())));
    }
    return String.join(System.lineSeparator(), lines) + System.lineSeparator();
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
