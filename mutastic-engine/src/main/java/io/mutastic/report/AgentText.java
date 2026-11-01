package io.mutastic.report;

import io.mutastic.engine.MutantResult;
import io.mutastic.engine.Report;
import io.mutastic.engine.Status;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/// `--format agent`: what a coding agent needs to close each gap, and nothing it would have to
/// skip. The verdict comes first, so a truncated read still has it; then each missed mutant
/// with the change, the evidence, the fix and how to prove it; then the lines no test reaches.
/// No colour, no progress, at most [#LIMIT] misses. Plain enough for people to read too.
public final class AgentText {
  static final int LIMIT = 10;
  static final int COVERING_SHOWN = 3;

  private final SourceView sources;
  private final int threshold;

  public AgentText(SourceView sources, int threshold) {
    this.sources = sources;
    this.threshold = threshold;
  }

  public String render(Report report) {
    var out = new StringBuilder();
    var missed = report.results().stream().filter(result -> result.status() == Status.MISSED).toList();
    var notCovered = report.results().stream().filter(result -> result.status() == Status.NOT_COVERED).toList();
    out.append("mutastic: %s · score %.1f%% (threshold %d) · %d missed, %d not covered, %d caught of %d mutants%n"
        .formatted(report.score() >= threshold ? "PASS" : "FAIL", report.score(), threshold, missed.size(),
            notCovered.size(), report.count(Status.CAUGHT) + report.count(Status.TIMED_OUT), report.results().size()));
    if (missed.isEmpty() && notCovered.isEmpty()) {
      return out.toString();
    }
    out.append("rules: add or change tests only, never production code; "
        + "a fix counts when `mutastic verify <id>` passes%n".formatted());
    int number = 0;
    for (MutantResult result : missed.stream().limit(LIMIT).toList()) {
      out.append(System.lineSeparator()).append(entry(++number, result));
    }
    if (missed.size() > LIMIT) {
      out.append("%n%d more missed mutants: run again after fixing these%n".formatted(missed.size() - LIMIT));
    }
    if (!notCovered.isEmpty()) {
      out.append("%nnot covered (no test reaches these lines; fix the missed mutants first):%n".formatted());
      var byLine = new LinkedHashMap<String, Long>();
      notCovered.forEach(result -> byLine.merge("%s:%d %s()".formatted(sources.file(result.mutant()),
          result.mutant().line(), result.mutant().methodName()), 1L, Long::sum));
      byLine.forEach((where, count) -> out.append("- %s (%d mutant%s)%n".formatted(where, count, count == 1 ? "" : "s")));
    }
    return out.toString();
  }

  /// One missed mutant, numbered, or unnumbered (0) where it stands alone.
  public String entry(int number, MutantResult result) {
    var mutant = result.mutant();
    var before = SourceView.originalOperator(mutant);
    var after = SourceView.mutatedOperator(mutant);
    var lines = new ArrayList<String>();
    lines.add("%smissed %s: no test checks the edge of `%s` in `%s()`".formatted(
        number == 0 ? "" : number + ". ", mutant.id(), before, mutant.methodName()));
    lines.add("   at %s:%d".formatted(sources.file(mutant), mutant.line()));
    sources.line(mutant).ifPresent(line -> lines.add("   - " + line));
    lines.add("   + " + sources.mutatedLine(mutant).orElse("(`%s` becomes `%s`)".formatted(before, after)));
    lines.add("   covered by: %s; all still pass with the change".formatted(covering(result.coveredBy())));
    lines.add("   fix: add a test with the values where `%s` and `%s` disagree (both sides equal), and assert the result"
        .formatted(before, after));
    lines.add("   reproduce: mutastic run --only=" + mutant.id());
    lines.add("   done when: mutastic verify %s passes".formatted(mutant.id()));
    return String.join(System.lineSeparator(), lines) + System.lineSeparator();
  }

  /// `PricingTest#bigOrders` for `com.example.PricingTest#bigOrders`.
  static String shortName(String test) {
    int hash = test.indexOf('#');
    var className = hash < 0 ? test : test.substring(0, hash);
    return className.substring(className.lastIndexOf('.') + 1) + (hash < 0 ? "" : test.substring(hash));
  }

  /// The fastest few, which are the ones to read first, and how many more there are.
  static String covering(List<String> tests) {
    var shown = tests.stream().limit(COVERING_SHOWN).map(AgentText::shortName).collect(Collectors.joining(", "));
    return tests.size() <= COVERING_SHOWN ? shown : "%s and %d more".formatted(shown, tests.size() - COVERING_SHOWN);
  }
}
