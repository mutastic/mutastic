package io.mutastic.report;

import io.mutastic.engine.Comparison;
import io.mutastic.engine.Mutant;
import io.mutastic.engine.MutantResult;
import io.mutastic.engine.Report;
import io.mutastic.engine.Status;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/// `--format full` and `--format concise`: for people at a terminal. Each missed mutant reads
/// like a compiler diagnostic about a missing test: the gap, the code with the change marked,
/// the evidence, the test to write. The summary comes last and ends on the score, because the
/// most important line belongs where the eye lands when the run ends.
public final class HumanText {
  /// Missed mutants shown as cards; the rest are counted.
  static final int CARDS = 50;

  private final SourceView sources;
  private final int threshold;
  private final Style style;
  private final boolean concise;
  private List<String> reports = List.of();

  public HumanText(SourceView sources, int threshold, Style style, boolean concise) {
    this.sources = sources;
    this.threshold = threshold;
    this.style = style;
    this.concise = concise;
  }

  /// @param setup how long reading the build took, before the engine started
  public String render(Report report, Optional<Comparison> comparison, Duration setup) {
    return render(report, comparison, setup, List.of());
  }

  /// @param reports the report files written, shown relative to the project
  public String render(Report report, Optional<Comparison> comparison, Duration setup, List<String> reports) {
    this.reports = reports;
    var out = new StringBuilder();
    var missed = grouped(report.results().stream().filter(result -> result.status() == Status.MISSED).toList());
    String file = null;
    for (MutantResult result : missed.stream().limit(CARDS).toList()) {
      var here = sources.file(result.mutant());
      if (!concise && !here.equals(file)) {
        out.append(fileHeader(here, result.mutant(), missed)).append(System.lineSeparator());
        file = here;
      }
      out.append(concise ? line(result) : card(result)).append(System.lineSeparator());
    }
    if (concise && !missed.isEmpty()) {
      out.append(System.lineSeparator());
    }
    if (missed.size() > CARDS) {
      out.append(style.dim("… and %d more missed mutants (--format=concise lists them all)".formatted(missed.size() - CARDS)))
          .append("\n\n");
    }
    notCovered(report, out);
    warnings(report, comparison, out);
    summary(report, comparison, setup, out);
    return out.toString();
  }

  /// One missed mutant on one line: `✗ path:line id method(): headline`.
  String line(MutantResult result) {
    var mutant = result.mutant();
    return "%s %s %s %s".formatted(style.red("✗"), location(mutant), style.dim(mutant.id()), Advice.of(mutant).headline());
  }

  /// Missed mutants by file, files in the order found; in each file, methods with the most
  /// misses first (where to start), then in source order.
  static List<MutantResult> grouped(List<MutantResult> missed) {
    var byFile = new LinkedHashMap<String, List<MutantResult>>();
    missed.forEach(result -> byFile.computeIfAbsent(outerClass(result.mutant()), file -> new ArrayList<>()).add(result));
    var ordered = new ArrayList<MutantResult>();
    for (var inFile : byFile.values()) {
      var perMethod = inFile.stream().collect(Collectors.groupingBy(HumanText::methodKey, Collectors.counting()));
      var firstSeen = new LinkedHashMap<String, Integer>();
      inFile.forEach(result -> firstSeen.putIfAbsent(methodKey(result), firstSeen.size()));
      inFile.stream()
          .sorted(Comparator.comparing((MutantResult result) -> -perMethod.get(methodKey(result)))
              .thenComparing(result -> firstSeen.get(methodKey(result))))
          .forEach(ordered::add);
    }
    return ordered;
  }

  /// `❯ src/…/Pricing.java  2 missed in 1 method`
  private String fileHeader(String file, Mutant mutant, List<MutantResult> missed) {
    var inFile = missed.stream().filter(result -> outerClass(result.mutant()).equals(outerClass(mutant))).toList();
    long methods = inFile.stream().map(HumanText::methodKey).distinct().count();
    var linked = sources.path(mutant).map(path -> style.link(path, file)).orElse(file);
    return "%s %s  %s".formatted(style.blue("❯"), style.bold(linked), style.dim("%d missed in %d method%s".formatted(
        inFile.size(), methods, methods == 1 ? "" : "s")));
  }

  /// `src/…/Pricing.java:17`, linked to the file when links are on.
  private String location(Mutant mutant) {
    var text = "%s:%d".formatted(sources.file(mutant), mutant.line());
    return sources.path(mutant).map(path -> style.link(path, text)).orElse(text);
  }

  private static String methodKey(MutantResult result) {
    return result.mutant().className() + "#" + result.mutant().methodName() + result.mutant().descriptor();
  }

  /// The class a source file is named for: nested classes share their outer class's file.
  private static String outerClass(Mutant mutant) {
    var name = mutant.className();
    int dollar = name.indexOf('$', name.lastIndexOf('/') + 1);
    return dollar < 0 ? name : name.substring(0, dollar);
  }

  /// A missed mutant as a card, rustc style.
  String card(MutantResult result) {
    var mutant = result.mutant();
    var advice = Advice.of(mutant);
    var lines = new ArrayList<String>();
    lines.add("%s %s %s".formatted(style.red("✗ missed[" + shortOperator(mutant) + "]"), style.dim(mutant.id()),
        style.bold(advice.headline())));
    lines.add("  %s %s".formatted(style.blue("-->"), location(mutant)));
    var file = sources.lines(mutant);
    int width = String.valueOf(mutant.line() + 1).length();
    var bar = style.blue(" ".repeat(width) + " |");
    var stillPass = result.coveredBy().size() == 1
        ? "its covering test still passes"
        : "all %d covering tests still pass".formatted(result.coveredBy().size());
    if (file.isPresent() && mutant.line() >= 1 && mutant.line() <= file.get().size()) {
      var source = file.get();
      lines.add(bar);
      for (int number = Math.max(1, mutant.line() - 1); number <= Math.min(source.size(), mutant.line() + 1); number++) {
        lines.add("%s %s".formatted(style.blue(pad(number, width) + " |"), source.get(number - 1)));
        if (number == mutant.line()) {
          lines.add("%s %s %s".formatted(bar, style.red(carets(source.get(number - 1), advice)),
              "%s and %s".formatted(advice.change(), stillPass)));
        }
      }
      lines.add(bar);
    }
    var mutated = sources.mutatedLine(mutant, advice);
    var before = sources.line(mutant);
    if (mutated.isPresent() && before.isPresent()) {
      lines.add("%s mutant  - %s".formatted(equals(width), before.get()));
      lines.add("%s         + %s".formatted(" ".repeat(width + 2), mutated.get()));
    }
    lines.add("%s note: covered by %s".formatted(equals(width), AgentText.covering(result.coveredBy())
        + result.unconfirmed().map(test -> " (%s failed once, then passed: flaky)".formatted(AgentText.shortName(test)))
            .orElse("")));
    if (!result.inputs().isEmpty()) {
      lines.add("%s note: inputs seen: %s".formatted(equals(width), AgentText.inputs(result.inputs())));
    }
    lines.add("%s %s %s".formatted(equals(width), style.cyan("help:"), advice.fix()));
    var pad = " ".repeat(width + 3 + "help: ".length());
    SuggestedTest.of(result, sources).ifPresent(test -> {
      lines.add(pad + style.dim("// in " + test.testClass()));
      test.lines().forEach(line -> lines.add(pad + style.green(line)));
      lines.add(pad + style.dim("check today's value is the one you intend: the test pins what the code does now"));
    });
    lines.add("%s reproduce: mutastic run --only=%s".formatted(equals(width), mutant.id()));
    lines.add("%s done when: mutastic verify %s passes".formatted(equals(width), mutant.id()));
    return String.join(System.lineSeparator(), lines) + System.lineSeparator();
  }

  /// Under the changed operator when the line has it once, else under the whole statement.
  private static String carets(String line, Advice advice) {
    int indent = line.length() - line.stripLeading().length();
    if (advice.before().isPresent()) {
      var token = " " + advice.before().get() + " ";
      int at = line.indexOf(token);
      if (at >= 0 && at == line.lastIndexOf(token)) {
        return " ".repeat(at + 1) + "^".repeat(advice.before().get().length());
      }
    }
    return " ".repeat(indent) + "^".repeat(Math.max(1, line.strip().length()));
  }

  private void notCovered(Report report, StringBuilder out) {
    var byLine = new LinkedHashMap<String, Long>();
    report.results().stream().filter(result -> result.status() == Status.NOT_COVERED).forEach(result ->
        byLine.merge("%s:%d %s()".formatted(sources.file(result.mutant()), result.mutant().line(),
            result.mutant().methodName()), 1L, Long::sum));
    if (byLine.isEmpty()) {
      return;
    }
    out.append(style.bold("Not covered") + style.dim(" (no test reaches these lines%s)".formatted(
        report.flaky().isEmpty() ? "" : ", or only a flaky one does"))).append('\n');
    byLine.entrySet().stream().limit(CARDS).forEach(entry -> out.append("  %s %s %s%n".formatted(
        style.amber("○"), entry.getKey(), style.dim("(%d mutant%s)".formatted(entry.getValue(), entry.getValue() == 1 ? "" : "s")))));
    if (byLine.size() > CARDS) {
      out.append(style.dim("  … and %d more lines%n".formatted(byLine.size() - CARDS)));
    }
    out.append('\n');
  }

  private void warnings(Report report, Optional<Comparison> comparison, StringBuilder out) {
    var lines = new ArrayList<String>();
    report.flaky().forEach(test -> lines.add("%s %s failed, then passed when run again; left out of this run".formatted(
        style.amber("! flaky:"), AgentText.shortName(test))));
    Suspects.changedBetweenRuns(comparison).forEach(suspect -> lines.add(
        "%s %s; with the code unchanged, whether it catches %s changed since the last run".formatted(
            style.amber("! likely flaky:"), AgentText.shortName(suspect.test()), String.join(", ", suspect.mutants()))));
    Suspects.unconfirmed(report).forEach(suspect -> lines.add("%s %s; its %s of %s didn't repeat when run again".formatted(
        style.amber("! likely flaky:"), AgentText.shortName(suspect.test()),
        suspect.mutants().size() == 1 ? "catch" : "catches", String.join(", ", suspect.mutants()))));
    Suspects.unrelatedCatches(report).forEach(note -> lines.add(
        "%s %s caught %d mutants in %d different methods, each with %s thrown in the test itself".formatted(
            style.dim("note:"), AgentText.shortName(note.test()), note.mutants().size(), note.methods(),
            note.failure().substring(note.failure().lastIndexOf('.') + 1))));
    if (!lines.isEmpty()) {
      lines.forEach(line -> out.append(line).append('\n'));
      out.append('\n');
    }
  }

  private void summary(Report report, Optional<Comparison> comparison, Duration setup, StringBuilder out) {
    var rows = new LinkedHashMap<String, String>();
    var skipped = report.count(Status.SKIPPED);
    rows.put("Mutants", "%s · %s · %s · %s %s%s".formatted(
        style.green("✓ %d caught".formatted(report.count(Status.CAUGHT))),
        report.count(Status.MISSED) == 0 ? "✗ 0 missed" : style.red("✗ %d missed".formatted(report.count(Status.MISSED))),
        "○ %d not covered".formatted(report.count(Status.NOT_COVERED)),
        "⏱ %d timed out".formatted(report.count(Status.TIMED_OUT)),
        style.dim("(" + report.scored() + ")"),
        skipped == 0 ? "" : style.dim(" · %d skipped".formatted(skipped))));
    var worst = worstMethods(report);
    if (!worst.isEmpty()) {
      rows.put("Worst at", worst);
    }
    if (!report.flaky().isEmpty()) {
      rows.put("Flaky", "%d test%s left out".formatted(report.flaky().size(), report.flaky().size() == 1 ? "" : "s"));
    }
    var mutantsTook = report.took().minus(report.coverage());
    rows.put("Duration", "%s %s".formatted(seconds(setup.plus(report.took())), style.dim("(setup %s, coverage %s, mutants %s)"
        .formatted(seconds(setup), seconds(report.coverage()), seconds(mutantsTook)))));
    rows.put("Tests", "%d on JDK %d · %d worker%s%s".formatted(report.tests(), report.jdk(), report.workers(),
        report.workers() == 1 ? "" : "s", report.replaced() == 0 ? ""
            : style.dim(" (%d replaced after a timeout)".formatted(report.replaced()))));
    if (!reports.isEmpty()) {
      rows.put("Reports", String.join(" · ", reports));
    }
    var score = "%.1f%%".formatted(report.score());
    boolean passed = report.score() >= threshold;
    rows.put("Score", "%s%s  %s".formatted(passed ? style.green(style.bold(score)) : style.red(score), delta(report, comparison),
        style.dim("threshold %d ".formatted(threshold)) + (passed ? style.green("✓") : style.red("✗"))));
    int width = rows.keySet().stream().mapToInt(String::length).max().orElse(0);
    rows.forEach((label, value) -> out.append("%s  %s%n".formatted(style.bold(" ".repeat(width - label.length() + 1) + label), value)));
    if (report.count(Status.MISSED) > 0) {
      out.append('\n').append(style.dim("Next: mutastic run --only=<id> · mutastic verify <id>")).append('\n');
    }
  }

  /// `  ▲ +2.0 vs last run`, when there was one.
  private String delta(Report report, Optional<Comparison> comparison) {
    return comparison.map(found -> {
      double change = Math.round((report.score() - found.previousScore()) * 10) / 10.0;
      var arrow = change > 0 ? "▲" : change < 0 ? "▼" : "=";
      return "  %s vs last run%s".formatted(style.dim(change == 0 ? arrow : "%s %+.1f".formatted(arrow, change)),
          style.dim(found.sameCode() ? " (same code)" : ""));
    }).orElse("");
  }

  /// The three methods with the most missed mutants: where to start.
  private static String worstMethods(Report report) {
    Map<String, Long> byMethod = report.results().stream().filter(result -> result.status() == Status.MISSED)
        .collect(Collectors.groupingBy(result -> methodLabel(result.mutant()), LinkedHashMap::new, Collectors.counting()));
    return byMethod.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
        .limit(3)
        .map(entry -> "%s %d ✗".formatted(entry.getKey(), entry.getValue()))
        .collect(Collectors.joining(" · "));
  }

  private static String methodLabel(Mutant mutant) {
    var simple = mutant.className().substring(mutant.className().lastIndexOf('/') + 1);
    return simple + "." + mutant.methodName() + "()";
  }

  /// `boundary` for `mutastic/boundary`.
  private static String shortOperator(Mutant mutant) {
    return mutant.operator().id().substring("mutastic/".length());
  }

  private String equals(int width) {
    return style.blue(" ".repeat(width) + " =");
  }

  private static String pad(int number, int width) {
    var text = String.valueOf(number);
    return " ".repeat(width - text.length()) + text;
  }

  /// `340ms`, `2.1s`, `1m39s`.
  public static String seconds(Duration duration) {
    long millis = duration.toMillis();
    if (millis < 1000) {
      return millis + "ms";
    }
    if (millis < 60_000) {
      return "%.1fs".formatted(millis / 1000.0);
    }
    return "%dm%02ds".formatted(millis / 60_000, (millis / 1000) % 60);
  }
}
