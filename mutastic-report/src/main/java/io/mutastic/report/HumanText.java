package io.mutastic.report;

import io.mutastic.api.Comparison;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// Output for people at a terminal, in four levels of detail: `full` cards, `brief` cards,
/// `concise` lines and `grouped` lines. Each missed mutant reads like a compiler diagnostic about a
/// missing test: the gap, the code with the change marked, the evidence, the test to write. The
/// summary comes last and ends on the score and the verdict, because the most important line
/// belongs where the eye lands when the run ends.
public final class HumanText {
  /// How much each missed mutant says.
  public enum Detail {
    /// Everything: frame, evidence, help, suggested test, commands.
    FULL,
    /// Headline, the changed line, the evidence, and the id.
    BRIEF,
    /// One line each: where, what, and the id.
    CONCISE,
    /// One line each, under its file and method, the method with the most misses first.
    GROUPED
  }

  /// What the run knows beyond the report: when it started, how long reading the build took, the
  /// report files written, and whether it was one mutant asked for by id.
  public record Run(LocalTime startedAt, Duration setup, List<String> reports, boolean oneMutant) {
    public Run {
      reports = List.copyOf(reports);
    }

    public Run(LocalTime startedAt, Duration setup, List<String> reports) {
      this(startedAt, setup, reports, false);
    }
  }

  /// Missed mutants shown as cards; the rest are counted.
  static final int CARDS = 50;

  private final SourceView sources;
  private final int threshold;
  private final Style style;
  private final Detail detail;
  private final Vocabulary words;

  public HumanText(
      SourceView sources, int threshold, Style style, Detail detail, Vocabulary words) {
    this.sources = sources;
    this.threshold = threshold;
    this.style = style;
    this.detail = detail;
    this.words = words;
  }

  public String render(Report report, Optional<Comparison> comparison, Run run) {
    var out = new StringBuilder();
    var missed =
        grouped(
            report.results().stream().filter(result -> result.status() == Status.MISSED).toList());
    if (!missed.isEmpty()) {
      out.append(heading());
      switch (detail) {
        case GROUPED -> out.append(groupedLines(missed));
        case CONCISE -> missed.forEach(result -> out.append(line(result)).append('\n'));
        default -> out.append(cards(missed));
      }
    }
    notCovered(report, out);
    warnings(report, comparison, out);
    summary(report, comparison, run, out);
    return out.toString();
  }

  /// The first [#CARDS] missed mutants as cards under their files, and how many more there are.
  private String cards(List<MutantResult> missed) {
    var out = new StringBuilder();
    String file = null;
    for (MutantResult result : missed.stream().limit(CARDS).toList()) {
      var here = sources.file(result.mutant());
      if (!here.equals(file)) {
        out.append('\n').append(fileHeader(here, result.mutant(), missed)).append('\n');
        file = here;
      }
      out.append(detail == Detail.BRIEF ? briefCard(result) : card(result));
    }
    if (missed.size() > CARDS) {
      out.append('\n')
          .append(
              style.dim(
                  " … and %d more %s mutants (--format=concise lists them all)"
                      .formatted(missed.size() - CARDS, words.undetected)))
          .append('\n');
    }
    return out.toString();
  }

  /// One file's results, as they finish: `✓` and a count when nothing was missed; `❯` with each
  /// miss and each line no test reaches otherwise.
  public String fileFinished(String project, List<MutantResult> results) {
    var missed = results.stream().filter(result -> result.status() == Status.MISSED).toList();
    var uncovered =
        results.stream().filter(result -> result.status() == Status.NOT_COVERED).toList();
    var lines = new ArrayList<String>(List.of(" " + fileHead(project, results, missed, uncovered)));
    missed.forEach(result -> lines.add(missedLine(result)));
    uncoveredByLine(uncovered).forEach((where, count) -> lines.add(uncoveredLine(where, count)));
    return String.join("\n", lines) + "\n";
  }

  /// `✓ project src/…/Pricing.java (12 mutants) 1.2s`: the file, its counts and how long it took.
  private String fileHead(
      String project,
      List<MutantResult> results,
      List<MutantResult> missed,
      List<MutantResult> uncovered) {
    var counts =
        new ArrayList<String>(
            List.of(
                style.dim(
                    "%d mutant%s".formatted(results.size(), results.size() == 1 ? "" : "s"))));
    if (!missed.isEmpty()) {
      counts.add(style.red("%d %s".formatted(missed.size(), words.undetected)));
    }
    if (!uncovered.isEmpty()) {
      counts.add(style.amber("%d %s".formatted(uncovered.size(), words.notCovered)));
    }
    var took = results.stream().map(MutantResult::took).reduce(Duration.ZERO, Duration::plus);
    return "%s %s %s %s %s"
        .formatted(
            missed.isEmpty() && uncovered.isEmpty() ? style.green("✓") : style.red("❯"),
            style.label(project, Style.CYAN),
            sources.file(results.getFirst().mutant()),
            style.dim("(") + String.join(style.dim(" | "), counts) + style.dim(")"),
            style.dim(HumanText.seconds(took)));
  }

  private String missedLine(MutantResult result) {
    return "   %s %s %s %s"
        .formatted(
            style.red("×"),
            plain(Advice.method(result.mutant())),
            style.dim(":" + sources.place(result.mutant()).line()),
            code(sources.advice(result.mutant()).change()));
  }

  /// `discount() :17` to how many mutants no test reaches there, in the order found.
  private Map<String, Long> uncoveredByLine(List<MutantResult> uncovered) {
    var byLine = new LinkedHashMap<String, Long>();
    uncovered.forEach(
        result ->
            byLine.merge(
                plain(Advice.method(result.mutant()))
                    + " :"
                    + sources.place(result.mutant()).line(),
                1L,
                Long::sum));
    return byLine;
  }

  private String uncoveredLine(String where, long count) {
    int colon = where.lastIndexOf(" :");
    return "   %s %s %s %s"
        .formatted(
            style.amber("○"),
            where.substring(0, colon),
            style.dim(where.substring(colon + 1)),
            style.dim(
                "no test reaches this line" + (count > 1 ? " (%d mutants)".formatted(count) : "")));
  }

  /// ` Missed ──────`
  private String heading() {
    return "\n"
        + style.red(style.bold(" " + words.heading + " "))
        + style.dim("─".repeat(60))
        + "\n";
  }

  /// One missed mutant on one line: `✗ path:line headline id`.
  String line(MutantResult result) {
    var mutant = result.mutant();
    return " %s %s %s %s"
        .formatted(
            style.red("✗"),
            location(mutant),
            code(sources.advice(result).headline()),
            style.dim(mutant.id()));
  }

  /// Under each file, each method with its misses, the method with the most first.
  private String groupedLines(List<MutantResult> missed) {
    var out = new StringBuilder();
    var byFile =
        missed.stream()
            .collect(
                Collectors.groupingBy(
                    result -> sources.file(result.mutant()),
                    LinkedHashMap::new,
                    Collectors.groupingBy(
                        HumanText::methodKey, LinkedHashMap::new, Collectors.toList())));
    byFile.forEach(
        (file, byMethod) -> {
          var path = sources.path(byMethod.values().iterator().next().getFirst().mutant());
          out.append("\n ")
              .append(style.bold(path.map(found -> style.link(found, file)).orElse(file)))
              .append('\n');
          byMethod.values().stream()
              .sorted(
                  Comparator.comparingInt((List<MutantResult> inMethod) -> inMethod.size())
                      .reversed())
              .forEach(inMethod -> appendMethodMisses(out, inMethod));
        });
    return out.toString();
  }

  /// A method and its count of misses, then each miss: `line:column ✗ headline id`.
  private void appendMethodMisses(StringBuilder out, List<MutantResult> inMethod) {
    out.append(
        "   %s %s%n"
            .formatted(
                plain(Advice.method(inMethod.getFirst().mutant())),
                style.red("%d %s".formatted(inMethod.size(), words.undetected))));
    for (MutantResult result : inMethod) {
      var place = sources.place(result.mutant());
      out.append(
          "     %s %s %s %s%n"
              .formatted(
                  style.dim("%-7s".formatted(place.line() + ":" + place.start())),
                  style.red("✗"),
                  code(sources.advice(result).headline()),
                  style.dim(result.mutant().id())));
    }
  }

  /// Missed mutants by file, files in the order found; in each file, methods with the most misses
  /// first (where to start), then in source order.
  static List<MutantResult> grouped(List<MutantResult> missed) {
    var byFile = new LinkedHashMap<String, List<MutantResult>>();
    missed.forEach(
        result ->
            byFile
                .computeIfAbsent(outerClass(result.mutant()), file -> new ArrayList<>())
                .add(result));
    var ordered = new ArrayList<MutantResult>();
    for (var inFile : byFile.values()) {
      var perMethod =
          inFile.stream()
              .collect(Collectors.groupingBy(HumanText::methodKey, Collectors.counting()));
      var firstSeen = new LinkedHashMap<String, Integer>();
      inFile.forEach(result -> firstSeen.putIfAbsent(methodKey(result), firstSeen.size()));
      inFile.stream()
          .sorted(
              Comparator.comparingLong(
                      (MutantResult result) -> -perMethod.getOrDefault(methodKey(result), 0L))
                  .thenComparingInt(result -> firstSeen.getOrDefault(methodKey(result), 0)))
          .forEach(ordered::add);
    }
    return ordered;
  }

  /// `❯ src/…/Pricing.java 2 missed in 1 method`
  private String fileHeader(String file, Mutant mutant, List<MutantResult> missed) {
    var inFile =
        missed.stream()
            .filter(result -> outerClass(result.mutant()).equals(outerClass(mutant)))
            .toList();
    long methods = inFile.stream().map(HumanText::methodKey).distinct().count();
    var linked = sources.path(mutant).map(path -> style.link(path, file)).orElse(file);
    return " %s %s  %s"
        .formatted(
            style.blue("❯"),
            style.bold(linked),
            style.dim(
                "%d %s in %d method%s"
                    .formatted(inFile.size(), words.undetected, methods, methods == 1 ? "" : "s")));
  }

  /// `src/…/Pricing.java:17`, linked to the file when links are on.
  private String location(Mutant mutant) {
    var text = "%s:%d".formatted(sources.file(mutant), sources.place(mutant).line());
    return sources.path(mutant).map(path -> style.link(path, text)).orElse(text);
  }

  private static String methodKey(MutantResult result) {
    return result.mutant().className()
        + "#"
        + result.mutant().methodName()
        + result.mutant().descriptor();
  }

  /// The class a source file is named for: nested classes share their outer class's file.
  private static String outerClass(Mutant mutant) {
    var name = mutant.className();
    int dollar = name.indexOf('$', name.lastIndexOf('/') + 1);
    return dollar < 0 ? name : name.substring(0, dollar);
  }

  /// A missed mutant in full: the label and the gap, the code with the change marked, the mutant as
  /// a before-and-after, the evidence, the help with a test to add, and the commands to reproduce
  /// it and prove the fix.
  String card(MutantResult result) {
    var mutant = result.mutant();
    var advice = sources.advice(result);
    var place = sources.place(mutant);
    int width = String.valueOf(place.line() + 1).length();
    var gutter = " ".repeat(width);
    var lines = new ArrayList<String>();
    lines.add("");
    addHead(lines, advice, mutant, gutter);
    lines.addAll(frame(mutant, advice, place, width));
    addBeforeAndAfter(lines, mutant, advice, gutter);
    addNotes(lines, gutter, cardNotes(result, advice));
    lines.add(annotation(gutter, style.green(style.bold("help")) + ": " + code(advice.fix())));
    var indent = " " + gutter + "         ";
    SuggestedTest.of(result, sources).ifPresent(test -> addSuggestedTest(lines, test, indent));
    lines.add("");
    for (MutantCommand command : MutantCommand.values()) {
      lines.add(command(gutter, command.label(), command.text(mutant.id(), sources.target())));
    }
    return String.join("\n", lines) + "\n";
  }

  /// `✗ MISSED headline`, then `┌─ src/…/Pricing.java:17`.
  private void addHead(List<String> lines, Advice advice, Mutant mutant, String gutter) {
    lines.add(
        " %s %s"
            .formatted(
                style.label("✗ " + words.undetected.toUpperCase(Locale.ROOT), Style.RED),
                style.bold(code(advice.headline()))));
    lines.add(" %s %s %s".formatted(gutter, style.dim("┌─"), location(mutant)));
  }

  /// The changed line between the lines either side, the change marked under it; nothing when the
  /// source isn't there.
  private List<String> frame(Mutant mutant, Advice advice, SourceView.Place place, int width) {
    var file = sources.lines(mutant).filter(all -> place.line() >= 1 && place.line() <= all.size());
    if (file.isEmpty()) {
      return List.of();
    }
    var source = file.get();
    var gutter = " ".repeat(width);
    var bar = style.dim("│");
    var lines = new ArrayList<String>();
    lines.add(" %s %s".formatted(gutter, bar));
    for (int number = Math.max(1, place.line() - 1);
        number <= Math.min(source.size(), place.line() + 1);
        number++) {
      lines.add(" %s %s %s".formatted(style.dim(pad(number, width)), bar, source.get(number - 1)));
      if (number == place.line()) {
        lines.add(caret(gutter, place, advice));
      }
    }
    lines.add(" %s %s".formatted(gutter, bar));
    return lines;
  }

  /// `^^ changed to >`, under the change.
  private String caret(String gutter, SourceView.Place place, Advice advice) {
    return " %s %s %s"
        .formatted(
            gutter,
            style.dim("│"),
            " ".repeat(place.start() - 1)
                + style.red("^".repeat(place.end() - place.start()) + " " + advice.changedTo()));
  }

  /// `= mutant - the line` and `+ the line mutated`, when it can be shown exactly.
  private void addBeforeAndAfter(List<String> lines, Mutant mutant, Advice advice, String gutter) {
    var mutated = sources.mutatedLine(mutant, advice);
    var before = sources.line(mutant);
    if (mutated.isPresent() && before.isPresent()) {
      lines.add(
          " %s %s mutant  %s".formatted(gutter, style.dim("="), style.red("- ") + before.get()));
      lines.add(" %s           %s".formatted(gutter, style.green("+ ") + mutated.get()));
    }
  }

  /// The advice's evidence, then which tests cover it and that they still pass.
  private static List<String> cardNotes(MutantResult result, Advice advice) {
    var notes = new ArrayList<>(advice.notes());
    notes.add(
        "covered by %s (%s)".formatted(AgentText.covering(result.coveredBy()), stillPass(result))
            + result
                .unconfirmed()
                .map(
                    test ->
                        "; %s failed once, then passed: flaky".formatted(AgentText.shortName(test)))
                .orElse(""));
    return notes;
  }

  /// `= note: the first`, the rest dim under it.
  private void addNotes(List<String> lines, String gutter, List<String> notes) {
    lines.add(annotation(gutter, style.blue(style.bold("note")) + ": " + code(notes.getFirst())));
    notes.stream()
        .skip(1)
        .forEach(note -> lines.add(" " + gutter + "         " + style.dim(code(note))));
  }

  /// ` │ = text`, a line the card adds below the code.
  private String annotation(String gutter, String text) {
    return " %s %s %s".formatted(gutter, style.dim("="), text);
  }

  private void addSuggestedTest(List<String> lines, SuggestedTest test, String indent) {
    lines.add("");
    lines.add(indent + style.dim("// in " + test.testClass()));
    test.lines()
        .forEach(line -> lines.add(indent + (line.startsWith("  ") ? style.green(line) : line)));
    lines.add(
        indent
            + style.dim(
                "check today's value is the one you intend: the test pins what the code does now"));
  }

  /// The card cut down: the gap, the changed line, the evidence and the id.
  String briefCard(MutantResult result) {
    var mutant = result.mutant();
    var advice = sources.advice(result);
    var place = sources.place(mutant);
    int width = String.valueOf(place.line()).length();
    var gutter = " ".repeat(width);
    var lines = new ArrayList<String>();
    lines.add("");
    addHead(lines, advice, mutant, gutter);
    sources
        .lines(mutant)
        .filter(all -> place.line() >= 1 && place.line() <= all.size())
        .ifPresent(source -> lines.addAll(changedLine(source, place, advice, gutter)));
    var evidence =
        advice.notes().isEmpty()
            ? List.of("covered by " + AgentText.covering(result.coveredBy()))
            : advice.notes();
    addNotes(lines, gutter, evidence);
    lines.add(annotation(gutter, idAndFullCard(mutant)));
    return String.join("\n", lines) + "\n";
  }

  /// `id: m855c8e3c · mutastic run … --format=full for the whole card`
  private String idAndFullCard(Mutant mutant) {
    return style.dim("id:")
        + " "
        + mutant.id()
        + " "
        + style.dim(
            "· %s --format=full for the whole card"
                .formatted(MutantCommand.REPRODUCE.command(mutant.id(), sources.target())));
  }

  /// Just the changed line, the change marked under it.
  private List<String> changedLine(
      List<String> source, SourceView.Place place, Advice advice, String gutter) {
    var bar = style.dim("│");
    return List.of(
        " %s %s".formatted(gutter, bar),
        " %s %s %s"
            .formatted(style.dim(String.valueOf(place.line())), bar, source.get(place.line() - 1)),
        caret(gutter, place, advice),
        " %s %s".formatted(gutter, bar));
  }

  private String command(String gutter, String label, String text) {
    return " %s %s %s".formatted(gutter, style.dim("%-10s".formatted(label)), text);
  }

  private static String stillPass(MutantResult result) {
    int count = result.coveredBy().size();
    return count == 1
        ? "its 1 covering test still passes"
        : "all %d covering tests still pass".formatted(count);
  }

  private void notCovered(Report report, StringBuilder out) {
    var byLine = notCoveredByLine(report);
    if (byLine.isEmpty()) {
      return;
    }
    out.append('\n')
        .append(style.amber(style.bold(" " + capitalise(words.notCovered) + " ")))
        .append(
            style.dim(
                "(no test reaches these lines%s)"
                    .formatted(report.flaky().isEmpty() ? "" : ", or only a flaky one does")))
        .append('\n');
    byLine.entrySet().stream()
        .limit(CARDS)
        .forEach(entry -> out.append(notCoveredLine(entry.getKey(), entry.getValue())));
    if (byLine.size() > CARDS) {
      out.append(style.dim("   … and %d more lines%n".formatted(byLine.size() - CARDS)));
    }
  }

  private String notCoveredLine(String where, long count) {
    return "   %s %s %s%n"
        .formatted(
            style.amber("○"),
            where,
            style.dim("(%d mutant%s)".formatted(count, count == 1 ? "" : "s")));
  }

  /// `src/…/Pricing.java:17 discount()` to how many mutants no test reaches there, in report order.
  private Map<String, Long> notCoveredByLine(Report report) {
    var byLine = new LinkedHashMap<String, Long>();
    report.results().stream()
        .filter(result -> result.status() == Status.NOT_COVERED)
        .forEach(
            result ->
                byLine.merge(
                    "%s:%d %s"
                        .formatted(
                            sources.file(result.mutant()),
                            sources.place(result.mutant()).line(),
                            plain(Advice.method(result.mutant()))),
                    1L,
                    Long::sum));
    return byLine;
  }

  private void warnings(Report report, Optional<Comparison> comparison, StringBuilder out) {
    var lines = new ArrayList<String>();
    report.flaky().forEach(test -> lines.add(flakyWarning(test)));
    Suspects.changedBetweenRuns(comparison)
        .forEach(suspect -> lines.add(changedBetweenRunsWarning(suspect)));
    Suspects.unconfirmed(report).forEach(suspect -> lines.add(unconfirmedWarning(suspect)));
    Suspects.unrelatedCatches(report).forEach(note -> lines.add(unrelatedCatchesNote(note)));
    if (!report.staleIgnores().isEmpty()) {
      lines.add(staleIgnoresWarning(report));
    }
    if (!lines.isEmpty()) {
      out.append('\n');
      lines.forEach(line -> out.append(line).append('\n'));
    }
  }

  private String staleIgnoresWarning(Report report) {
    var ids = report.staleIgnores().stream().map(io.mutastic.api.Ignore::id).toList();
    return " %s %s: %s code changed or is gone, so %s mutant%s ran; remove with `mutastic ignore --prune`"
        .formatted(
            style.amber(
                ids.size() == 1 ? "! an ignore no longer holds:" : "! ignores no longer hold:"),
            String.join(", ", ids),
            ids.size() == 1 ? "its" : "their",
            ids.size() == 1 ? "its" : "their",
            ids.size() == 1 ? "" : "s");
  }

  private String flakyWarning(String test) {
    return " %s %s failed, then passed when run again; left out of this run"
        .formatted(style.amber("! flaky:"), AgentText.shortName(test));
  }

  private String changedBetweenRunsWarning(Suspects.Suspect suspect) {
    return " %s %s; with the code unchanged, whether it catches %s changed since the last run"
        .formatted(
            style.amber("! likely flaky:"),
            AgentText.shortName(suspect.test()),
            String.join(", ", suspect.mutants()));
  }

  private String unconfirmedWarning(Suspects.Suspect suspect) {
    return " %s %s; its %s of %s didn't repeat when run again"
        .formatted(
            style.amber("! likely flaky:"),
            AgentText.shortName(suspect.test()),
            suspect.mutants().size() == 1 ? "catch" : "catches",
            String.join(", ", suspect.mutants()));
  }

  private String unrelatedCatchesNote(Suspects.UnrelatedCatches note) {
    return " %s %s caught %d mutants in %d different methods, each with %s thrown in the test itself"
        .formatted(
            style.dim("note:"),
            AgentText.shortName(note.test()),
            note.mutants().size(),
            note.methods(),
            note.failure().substring(note.failure().lastIndexOf('.') + 1));
  }

  private void summary(Report report, Optional<Comparison> comparison, Run run, StringBuilder out) {
    appendRows(out, rows(report, comparison, run));
    out.append('\n');
    out.append(verdict(report, run));
    if (report.count(Status.MISSED) > 0 && !run.oneMutant()) {
      out.append(style.dim(" Next: " + MutantCommand.next(sources.target()))).append('\n');
    }
  }

  /// The summary's rows, label to value, in the order shown.
  private Map<String, String> rows(Report report, Optional<Comparison> comparison, Run run) {
    var rows = new LinkedHashMap<String, String>();
    rows.put("Classes", classesRow(report));
    rows.put("Mutants", mutantsRow(report));
    putWhenThere(rows, report);
    rows.put("Test runs", testRunsRow(report));
    rows.put("Start at", run.startedAt().format(DateTimeFormatter.ofPattern("HH:mm:ss")));
    rows.put("Duration", durationRow(report, run));
    rows.put("Tests", testsRow(report));
    if (!run.reports().isEmpty()) {
      rows.put("Reports", String.join(style.dim(" · "), run.reports()));
    }
    if (!run.oneMutant()) { // one mutant has a fate, not a score
      rows.put("Score", scoreRow(report, comparison));
    }
    return rows;
  }

  /// The rows only some runs have: the change it was scoped to, an audit, where the misses are,
  /// flaky tests.
  private void putWhenThere(Map<String, String> rows, Report report) {
    report
        .scope()
        .ifPresent(
            scope ->
                rows.put(
                    "Changed",
                    "%s in %s since %s"
                        .formatted(
                            count(scope.lineCount(), "line"),
                            count(scope.files(), "file"),
                            scope.since())));
    var audit = AridAudit.of(report);
    if (audit.happened()) {
      rows.put("Arid audit", audit.summary());
    }
    var worst = worstMethods(report);
    if (!worst.isEmpty()) {
      rows.put("Worst at", worst);
    }
    if (!report.flaky().isEmpty()) {
      rows.put("Flaky", flakyRow(report));
    }
  }

  /// `1 line`, `8 lines`.
  private static String count(int number, String noun) {
    return number + " " + noun + (number == 1 ? "" : "s");
  }

  /// Each row, its label right-aligned to the longest.
  private void appendRows(StringBuilder out, Map<String, String> rows) {
    int width = rows.keySet().stream().mapToInt(String::length).max().orElse(0);
    out.append('\n');
    rows.forEach(
        (label, value) ->
            out.append(
                " %s  %s%n"
                    .formatted(style.dim(" ".repeat(width - label.length()) + label), value)));
  }

  private String classesRow(Report report) {
    var files = filesByOutcome(report);
    return String.join(
            style.dim(" | "),
            Stream.of(
                    style.green(style.bold(files[0] + " clean")),
                    files[1] == 0
                        ? null
                        : style.red(style.bold(files[1] + " " + words.classesWith)))
                .filter(java.util.Objects::nonNull)
                .toList())
        + style.dim(" (" + (files[0] + files[1]) + ")");
  }

  private String mutantsRow(Report report) {
    long caught = report.count(Status.CAUGHT);
    long missed = report.count(Status.MISSED);
    long timedOut = report.count(Status.TIMED_OUT);
    long notCovered = report.count(Status.NOT_COVERED);
    return String.join(
            style.dim(" · "),
            Stream.of(
                    style.green("✓ " + style.bold(caught + " " + words.detected)),
                    missed == 0
                        ? null
                        : style.red("✗ " + style.bold(missed + " " + words.undetected)),
                    timedOut == 0 ? null : style.amber("⏱ " + timedOut + " timed out"),
                    notCovered == 0
                        ? null
                        : style.amber("○ " + notCovered + " " + words.notCovered),
                    report.count(Status.SKIPPED) == 0 ? null : style.dim(skippedByKind(report)))
                .filter(java.util.Objects::nonNull)
                .toList())
        + style.dim(" (" + report.results().size() + ")");
  }

  /// The test runs made, and how many there would have been running every test on every mutant.
  private String testRunsRow(Report report) {
    long runs = report.results().stream().mapToLong(MutantResult::testsRun).sum();
    long ran =
        report.results().stream()
            .filter(
                result ->
                    result.status() != Status.SKIPPED && result.status() != Status.NOT_COVERED)
            .count();
    return runs + style.dim(" (%d without coverage selection)".formatted(ran * report.tests()));
  }

  private String durationRow(Report report, Run run) {
    return "%s %s"
        .formatted(
            seconds(run.setup().plus(report.took())),
            style.dim(
                "(setup %s, coverage %s, mutants %s)"
                    .formatted(
                        seconds(run.setup()),
                        seconds(report.coverage()),
                        seconds(report.took().minus(report.coverage())))));
  }

  private static String flakyRow(Report report) {
    return "%d test%s left out"
        .formatted(report.flaky().size(), report.flaky().size() == 1 ? "" : "s");
  }

  private String testsRow(Report report) {
    if (report.tests() == 0) {
      return "none found (mutastic runs JUnit Platform tests)";
    }
    return "%d on JDK %d · %d worker%s%s"
        .formatted(
            report.tests(),
            report.jdk(),
            report.workers(),
            report.workers() == 1 ? "" : "s",
            report.replaced() == 0
                ? ""
                : style.dim(" (%d replaced after a timeout)".formatted(report.replaced())));
  }

  private String scoreRow(Report report, Optional<Comparison> comparison) {
    if (report.score().isEmpty()) {
      return style.bold("none") + style.dim(" (no mutants to score)  threshold " + threshold);
    }
    var score = "%.1f%%".formatted(report.score().getAsDouble());
    return (report.passes(threshold)
            ? style.green(style.bold(score))
            : style.red(style.bold(score)))
        + delta(report, comparison)
        + style.dim("  threshold " + threshold);
  }

  /// The last line: the one mutant's fate, or PASS or FAIL with why.
  private String verdict(Report report, Run run) {
    long missed = report.count(Status.MISSED);
    if (run.oneMutant() && !report.results().isEmpty()) {
      return " %s%n".formatted(fate(report.results().getFirst()));
    }
    if (report.score().isEmpty()) {
      return " %s %s%n"
          .formatted(
              style.label("PASS", Style.GREEN),
              style.dim("Nothing to measure: no mutants to score."));
    }
    if (report.passes(threshold)) {
      return " %s %s%n"
          .formatted(
              style.label("PASS", Style.GREEN),
              style.green(
                  missed == 0
                      ? words.allDetected
                      : "%d %s, above the threshold.".formatted(missed, words.undetected)));
    }
    return failed(missed, report.count(Status.NOT_COVERED));
  }

  private String failed(long missed, long notCovered) {
    return " %s %s%n"
        .formatted(
            style.label("FAIL", Style.RED),
            style.red(
                "Score below %d: %d %s, %d %s."
                    .formatted(threshold, missed, words.undetected, notCovered, words.notCovered)));
  }

  /// `[MISSED] m855c8e3c is still missed`: one mutant's verdict, for a run that asked for it.
  private String fate(MutantResult result) {
    var id = result.mutant().id();
    return switch (result.status()) {
      case CAUGHT ->
          detectedFate(
              "%s is %s by %s"
                  .formatted(
                      id, words.detected, AgentText.shortName(result.caughtBy().orElse("a test"))));
      case TIMED_OUT ->
          detectedFate(
              "%s is %s: its tests stopped finishing in time".formatted(id, words.detected));
      case MISSED ->
          redFate(
              words.undetected,
              "%s is still %s · done when %s"
                  .formatted(
                      id, words.undetected, MutantCommand.DONE_WHEN.text(id, sources.target())));
      case NOT_COVERED -> redFate(words.notCovered, "%s: no test reaches its line".formatted(id));
      case SKIPPED ->
          style.label("SKIPPED", Style.CYAN)
              + " "
              + "%s is skipped: %s".formatted(id, result.mutant().skipped().orElse(""));
    };
  }

  private String detectedFate(String text) {
    return style.label(words.detected.toUpperCase(Locale.ROOT), Style.GREEN)
        + " "
        + style.green(text);
  }

  private String redFate(String label, String text) {
    return style.label(label.toUpperCase(Locale.ROOT), Style.RED) + " " + style.red(text);
  }

  /// {clean, with misses}: source files with mutants, by whether any was missed or not covered.
  private static long[] filesByOutcome(Report report) {
    var byFile =
        report.results().stream()
            .collect(
                Collectors.groupingBy(
                    result -> outerClass(result.mutant()),
                    Collectors.mapping(MutantResult::status, Collectors.toSet())));
    long withMisses =
        byFile.values().stream()
            .filter(
                statuses ->
                    statuses.contains(Status.MISSED) || statuses.contains(Status.NOT_COVERED))
            .count();
    return new long[] {byFile.size() - withMisses, withMisses};
  }

  /// `2 skipped (1 equivalent, 1 generated)`: the kind is each reason's first word.
  private static String skippedByKind(Report report) {
    var byKind = new TreeMap<String, Long>();
    report.results().stream()
        .filter(result -> result.status() == Status.SKIPPED)
        .forEach(
            result -> {
              var reason = result.mutant().skipped().orElse("other: ?");
              byKind.merge(reason.substring(0, Math.max(0, reason.indexOf(':'))), 1L, Long::sum);
            });
    return "%d skipped (%s)"
        .formatted(
            report.count(Status.SKIPPED),
            byKind.entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey())
                .collect(Collectors.joining(", ")));
  }

  /// ` ▲ 2.0 vs last run (same code)`, when there was one.
  private String delta(Report report, Optional<Comparison> comparison) {
    var now = report.score();
    return comparison
        .filter(found -> now.isPresent() && found.previousScore().isPresent())
        .map(
            found -> {
              double change =
                  Math.round((now.getAsDouble() - found.previousScore().getAsDouble()) * 10) / 10.0;
              var sameness = style.dim(" (" + found.sameness() + ")");
              if (change == 0) {
                return style.dim("  = vs last run") + sameness;
              }
              var text = "%s %.1f vs last run".formatted(change > 0 ? "▲" : "▼", Math.abs(change));
              return "  " + (change > 0 ? style.green(text) : style.red(text)) + sameness;
            })
        .orElse("");
  }

  /// The three methods with the most missed mutants: where to start.
  private static String worstMethods(Report report) {
    Map<String, Long> byMethod =
        report.results().stream()
            .filter(result -> result.status() == Status.MISSED)
            .collect(
                Collectors.groupingBy(
                    result -> methodLabel(result.mutant()),
                    LinkedHashMap::new,
                    Collectors.counting()));
    return byMethod.entrySet().stream()
        .sorted(Map.Entry.<String, Long>comparingByValue(Comparator.reverseOrder()))
        .limit(3)
        .map(entry -> "%s %d ✗".formatted(entry.getKey(), entry.getValue()))
        .collect(Collectors.joining(" · "));
  }

  private static String methodLabel(Mutant mutant) {
    var simple = mutant.className().substring(mutant.className().lastIndexOf('/') + 1);
    return simple + "." + plain(Advice.method(mutant));
  }

  /// Backticks mark code in the templates; in the terminal they become bold text instead.
  private String code(String text) {
    var out = new StringBuilder();
    var parts = text.split("`", -1);
    for (int i = 0; i < parts.length; i++) {
      out.append(i % 2 == 1 ? style.bold(parts[i]) : parts[i]);
    }
    return out.toString();
  }

  private static String plain(String text) {
    return text.replace("`", "");
  }

  private static String capitalise(String text) {
    return Character.toUpperCase(text.charAt(0)) + text.substring(1);
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
