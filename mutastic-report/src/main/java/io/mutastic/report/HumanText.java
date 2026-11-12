package io.mutastic.report;

import io.mutastic.api.ClassMutator;
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
  ///
  /// @param leftOut the test classes `--exclude-tests` left out, which the summary names
  public record Run(
      LocalTime startedAt,
      Duration setup,
      List<String> reports,
      boolean oneMutant,
      List<String> leftOut) {
    public Run {
      reports = List.copyOf(reports);
      leftOut = List.copyOf(leftOut);
    }

    public Run(LocalTime startedAt, Duration setup, List<String> reports, boolean oneMutant) {
      this(startedAt, setup, reports, oneMutant, List.of());
    }

    public Run(LocalTime startedAt, Duration setup, List<String> reports) {
      this(startedAt, setup, reports, false);
    }
  }

  /// Missed mutants shown as cards; the rest are counted.
  static final int CARDS = 50;

  /// Cards shown for one file, unless `--each-mutant`, as more than a few at a time are left
  /// unread. Seven is Google's number, used differently: its code review surfaces a budget of seven
  /// mutants for each file a change touches, across the change and drawn at random (Petrović et
  /// al., TSE 2021), where this caps each file and ranks what it shows.
  static final int PER_FILE = 7;

  private final SourceView sources;
  private final int threshold;
  private final Style style;
  private final Detail detail;
  private final Vocabulary words;
  private final boolean eachMutant;

  public HumanText(
      SourceView sources, int threshold, Style style, Detail detail, Vocabulary words) {
    this(sources, threshold, style, detail, words, false);
  }

  private HumanText(
      SourceView sources,
      int threshold,
      Style style,
      Detail detail,
      Vocabulary words,
      boolean eachMutant) {
    this.sources = sources;
    this.threshold = threshold;
    this.style = style;
    this.detail = detail;
    this.words = words;
    this.eachMutant = eachMutant;
  }

  /// The same, with a card for every missed mutant, not one for each line's (`--each-mutant`).
  public HumanText eachMutant(boolean each) {
    return new HumanText(sources, threshold, style, detail, words, each);
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
    if (GhostCards.any(report)) {
      out.append('\n').append(new GhostCards(sources).render(report));
    }
    warnings(report, comparison, out);
    summary(report, comparison, run, out);
    return out.toString();
  }

  /// The first [#CARDS] findings as cards under their files, and how many more missed mutants there
  /// are.
  private String cards(List<MutantResult> missed) {
    var out = new StringBuilder();
    int shown = 0;
    int unshown = 0;
    for (var inFile : byFile(Finding.of(missed, eachMutant))) {
      int room = Math.min(eachMutant ? CARDS : PER_FILE, CARDS - shown);
      if (room == 0) {
        unshown += Finding.mutantsIn(inFile);
      } else {
        appendFile(out, inFile, room, missed);
        shown += Math.min(room, inFile.size());
      }
    }
    if (unshown > 0) {
      out.append('\n')
          .append(
              style.dim(
                  " … and %d more %s mutants (--format=concise lists them all)"
                      .formatted(unshown, words.undetected)))
          .append('\n');
    }
    return out.toString();
  }

  /// The findings of each file, files in the order found.
  private List<List<Finding>> byFile(List<Finding> findings) {
    var byFile = new LinkedHashMap<String, List<Finding>>();
    for (Finding finding : findings) {
      byFile
          .computeIfAbsent(sources.file(finding.lead().mutant()), file -> new ArrayList<>())
          .add(finding);
    }
    return List.copyOf(byFile.values());
  }

  /// One file's header and its first `room` cards, and how many more missed mutants it has.
  private void appendFile(
      StringBuilder out, List<Finding> inFile, int room, List<MutantResult> missed) {
    var lead = inFile.getFirst().lead().mutant();
    out.append('\n').append(fileHeader(sources.file(lead), lead, missed)).append('\n');
    inFile.stream()
        .limit(room)
        .forEach(
            finding -> out.append(detail == Detail.BRIEF ? briefCard(finding) : card(finding)));
    if (inFile.size() > room) {
      out.append('\n')
          .append(
              style.dim(
                  "   … and %d more %s mutants in this file (--each-mutant shows every card)"
                      .formatted(
                          Finding.mutantsIn(inFile.subList(room, inFile.size())),
                          words.undetected)))
          .append('\n');
    }
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
        "   %s %s\n"
            .formatted(
                plain(Advice.method(inMethod.getFirst().mutant())),
                style.red("%d %s".formatted(inMethod.size(), words.undetected))));
    for (MutantResult result : inMethod) {
      var place = sources.place(result.mutant());
      out.append(
          "     %s %s %s %s\n"
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
    return card(new Finding(List.of(result)));
  }

  /// A finding in full: its lead mutant's card, with each other mutant on the line as its change,
  /// each distinct test to add, and the commands for them all.
  String card(Finding finding) {
    var result = finding.lead();
    var mutant = result.mutant();
    var advice = sources.advice(result);
    var place = sources.place(mutant);
    int width = String.valueOf(place.line() + 1).length();
    var gutter = " ".repeat(width);
    var lines = new ArrayList<String>();
    lines.add("");
    addHead(lines, advice, gutter, finding);
    lines.addAll(frame(mutant, advice, place, width));
    addBeforeAndAfter(lines, mutant, advice, gutter);
    finding.others().forEach(other -> addOther(lines, other, gutter));
    addNotes(lines, gutter, cardNotes(result, advice));
    addHelp(lines, finding, advice, gutter);
    lines.add("");
    addCommands(lines, finding, gutter, MutantCommand.withAMutant());
    return String.join("\n", lines) + "\n";
  }

  /// `= also ✗ headline · m2e491c40`, then `+ the line` with its change, when it can be shown.
  private void addOther(List<String> lines, MutantResult other, String gutter) {
    var diff = sources.diff(other.mutant(), sources.advice(other));
    lines.add(annotation(gutter, alsoMissed(other)));
    if (diff.shown() && !diff.removed()) {
      diff.shownAfter()
          .forEach(
              line -> lines.add(" %s           %s".formatted(gutter, style.green("+ ") + line)));
    } else {
      lines.add(changeInWords(gutter, diff.removed() ? diff.change() : diff.unshown()));
    }
  }

  /// `= change the change in words`, for a change the card shows no `+` line for.
  private String changeInWords(String gutter, String words) {
    return " %s %s change  %s".formatted(gutter, style.dim("="), code(words));
  }

  /// One mutant in full whatever became of it, for `explain`: a miss gets its card; anything else a
  /// card that says what happened instead, without help or a test to add.
  public String explained(MutantResult result) {
    if (result.status() == Status.MISSED) {
      return card(result);
    }
    var mutant = result.mutant();
    var advice = sources.advice(result);
    var place = sources.place(mutant);
    int width = String.valueOf(place.line() + 1).length();
    var gutter = " ".repeat(width);
    var lines = new ArrayList<String>();
    lines.add("");
    lines.add(" %s %s".formatted(outcomeLabel(result), style.bold(code(outcome(result)))));
    lines.add(" %s %s %s".formatted(gutter, style.dim("┌─"), location(mutant)));
    lines.addAll(frame(mutant, advice, place, width));
    addBeforeAndAfter(lines, mutant, advice, gutter);
    var notes = explainedNotes(result, advice);
    if (!notes.isEmpty()) {
      addNotes(lines, gutter, notes);
    }
    lines.add("");
    // Caught, or not to be caught: done when and ignore don't apply.
    addCommands(lines, new Finding(List.of(result)), gutter, List.of(MutantCommand.REPRODUCE));
    return String.join("\n", lines) + "\n";
  }

  private static List<String> explainedNotes(MutantResult result, Advice advice) {
    var notes = new ArrayList<>(advice.notes());
    if (!result.coveredBy().isEmpty()) {
      notes.add(AgentText.testsRun(result, " "));
    }
    result.falseCatchBy().ifPresent(test -> notes.add(AgentText.falseCatch(test)));
    return notes;
  }

  private String outcomeLabel(MutantResult result) {
    return switch (result.status()) {
      case CAUGHT, TIMED_OUT ->
          style.label("✓ " + words.detected.toUpperCase(Locale.ROOT), Style.GREEN);
      case NOT_COVERED -> style.label("○ NOT COVERED", Style.CYAN);
      default -> style.label("SKIPPED", Style.CYAN);
    };
  }

  private static String outcome(MutantResult result) {
    return switch (result.status()) {
      case CAUGHT ->
          "caught by %s%s"
              .formatted(
                  AgentText.shortName(result.caughtBy().orElse("a test")),
                  result
                      .failureOrigin()
                      .map(
                          origin ->
                              origin.equals("assertion")
                                  ? ", by an assertion"
                                  : ", by a crash in the " + origin)
                      .orElse(""));
      case TIMED_OUT -> "its tests stopped finishing in time, which counts as caught";
      case NOT_COVERED -> "no test reaches this line";
      case SKIPPED -> result.mutant().skipped().orElse("");
      case MISSED -> "missed";
    };
  }

  /// `done when`, `reproduce`, `or ignore`: the commands for one mutant, its outcome styled like
  /// its label, so `passes` doesn't read as part of what to type.
  private void addCommands(
      List<String> lines, Finding finding, String gutter, List<MutantCommand> commands) {
    for (MutantCommand command : commands) {
      var text = command.text(finding, sources.invocation());
      var outcome = command.outcome();
      lines.add(
          command(
              gutter,
              command.label(),
              outcome.isEmpty() || !text.endsWith(outcome)
                  ? text
                  : text.substring(0, text.length() - outcome.length()) + style.dim(outcome)));
    }
  }

  /// `✗ MISSED headline`, then `┌─ src/…/Pricing.java:17`, for the finding's lead; with its id
  /// after the headline when the card has others, as each of theirs follows its own. The label,
  /// then the headline: plain words with its code bold. A bold around it all would be ended by the
  /// first code term's reset, leaving its start bold and the rest plain.
  private void addHead(List<String> lines, Advice advice, String gutter, Finding finding) {
    var mutant = finding.lead().mutant();
    lines.add(
        " %s %s%s"
            .formatted(
                style.label("✗ " + words.undetected.toUpperCase(Locale.ROOT), Style.RED),
                code(advice.headline()),
                finding.mutants().size() > 1 ? style.dim(" · " + mutant.id()) : ""));
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

  /// `= mutant - the statement` and `+ the statement mutated`, when the source shows the change
  /// exactly; otherwise `= change the change in words`, never words where code goes.
  private void addBeforeAndAfter(List<String> lines, Mutant mutant, Advice advice, String gutter) {
    var diff = sources.diff(mutant, advice);
    if (!diff.shown()) {
      lines.add(changeInWords(gutter, diff.unshown()));
      return;
    }
    var before = diff.shownBefore();
    lines.add(
        " %s %s mutant  %s".formatted(gutter, style.dim("="), style.red("- ") + before.getFirst()));
    before.stream()
        .skip(1)
        .forEach(line -> lines.add(" %s           %s".formatted(gutter, style.red("- ") + line)));
    diff.shownAfter()
        .forEach(line -> lines.add(" %s           %s".formatted(gutter, style.green("+ ") + line)));
  }

  /// The advice's evidence, then which tests cover it and that they still pass.
  private static List<String> cardNotes(MutantResult result, Advice advice) {
    var notes = new ArrayList<>(advice.notes());
    notes.add(
        "%s (%s)".formatted(AgentText.testsRun(result, " "), stillPass(result))
            + result
                .unconfirmed()
                .map(
                    test ->
                        "; %s failed once, then passed: flaky".formatted(AgentText.shortName(test)))
                .orElse(""));
    result.falseCatchBy().ifPresent(test -> notes.add(AgentText.falseCatch(test)));
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

  /// `= help: the fix`, then each test to add. When the card has a test for more than one of its
  /// mutants, each test comes after its own mutant's fix and id, since the lead's fix describes
  /// only the lead's test.
  private void addHelp(List<String> lines, Finding finding, Advice advice, String gutter) {
    var indent = " " + gutter + "         ";
    var each = SuggestedTest.eachMutant(finding, sources);
    if (!SuggestedTest.eachHasItsOwn(each)) {
      lines.add(annotation(gutter, style.green(style.bold("help")) + ": " + code(advice.fix())));
      SuggestedTest.distinct(finding, sources)
          .forEach(test -> addSuggestedTest(lines, test, indent));
      return;
    }
    lines.add(
        annotation(gutter, style.green(style.bold("help")) + ": " + SuggestedTest.eachHelp(each)));
    for (SuggestedTest.ForMutant one : each) {
      lines.add("");
      lines.add(
          indent
              + code(sources.advice(one.result()).fix())
              + style.dim(" · " + one.result().mutant().id()));
      one.test().ifPresent(test -> addTestLines(lines, test, indent));
    }
  }

  private void addSuggestedTest(List<String> lines, SuggestedTest test, String indent) {
    lines.add("");
    addTestLines(lines, test, indent);
  }

  private void addTestLines(List<String> lines, SuggestedTest test, String indent) {
    lines.add(indent + style.dim("// in " + SourceNames.sourceName(test.testClass())));
    test.lines()
        .forEach(line -> lines.add(indent + (line.startsWith("  ") ? style.green(line) : line)));
    lines.add(
        indent
            + style.dim(
                "check today's value is the one you intend: the test pins what the code does now"));
  }

  /// The card cut down: the gap, the changed line, the evidence and the id.
  String briefCard(MutantResult result) {
    return briefCard(new Finding(List.of(result)));
  }

  /// A finding cut down: the lead's gap and changed line, each other mutant's headline, the
  /// evidence and the ids.
  String briefCard(Finding finding) {
    var result = finding.lead();
    var mutant = result.mutant();
    var advice = sources.advice(result);
    var place = sources.place(mutant);
    int width = String.valueOf(place.line()).length();
    var gutter = " ".repeat(width);
    var lines = new ArrayList<String>();
    lines.add("");
    addHead(lines, advice, gutter, finding);
    sources
        .lines(mutant)
        .filter(all -> place.line() >= 1 && place.line() <= all.size())
        .ifPresent(source -> lines.addAll(changedLine(source, place, advice, gutter)));
    var evidence =
        advice.notes().isEmpty() ? List.of(AgentText.testsRun(result, " ")) : advice.notes();
    finding.others().forEach(other -> lines.add(annotation(gutter, alsoMissed(other))));
    addNotes(lines, gutter, evidence);
    lines.add(annotation(gutter, idAndFullCard(finding)));
    return String.join("\n", lines) + "\n";
  }

  /// `also ✗ headline · m2e491c40`
  private String alsoMissed(MutantResult other) {
    return "also    %s %s %s"
        .formatted(
            style.red("✗"),
            code(sources.advice(other).headline()),
            style.dim("· " + other.mutant().id()));
  }

  /// `id: m855c8e3c · mutastic run … --format=full for the whole card`
  private String idAndFullCard(Finding finding) {
    return style.dim(finding.mutants().size() == 1 ? "id:" : "ids:")
        + " "
        + finding.ids().replace(",", ", ")
        + " "
        + style.dim(
            "· %s --format=full for the whole card"
                .formatted(MutantCommand.REPRODUCE.command(finding.ids(), sources.invocation())));
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
    if (AgentText.ranEveryTest(result)) {
      return count == 1 ? "it still passes" : "all %d still pass".formatted(count);
    }
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
    appendNotCoveredLines(out, byLine);
    var uncovered =
        report.results().stream().filter(result -> result.status() == Status.NOT_COVERED).toList();
    SuggestedTest.startingPoints(uncovered, sources, 1)
        .forEach(
            assertion ->
                out.append("     %s %s\n".formatted(style.dim("try:"), style.green(assertion))));
  }

  /// The first [#CARDS] places no test reaches, and how many more there are.
  private void appendNotCoveredLines(StringBuilder out, Map<String, Long> byLine) {
    byLine.entrySet().stream()
        .limit(CARDS)
        .forEach(entry -> out.append(notCoveredLine(entry.getKey(), entry.getValue())));
    if (byLine.size() > CARDS) {
      out.append(
          style.dim(
              "   … and %d more %s\n"
                  .formatted(byLine.size() - CARDS, eachMutant ? "lines" : "methods")));
    }
  }

  private String notCoveredLine(String where, long count) {
    return "   %s %s %s\n"
        .formatted(
            style.amber("○"),
            where,
            style.dim("(%d mutant%s)".formatted(count, count == 1 ? "" : "s")));
  }

  /// `src/…/Pricing.java:17 discount()` to how many mutants no test reaches there, in report order:
  /// one entry per method, at its first such line, or with `--each-mutant` one per line.
  private Map<String, Long> notCoveredByLine(Report report) {
    var uncovered =
        report.results().stream().filter(result -> result.status() == Status.NOT_COVERED).toList();
    return NotCovered.counted(
        uncovered, sources, eachMutant, mutant -> plain(Advice.method(mutant)));
  }

  private void warnings(Report report, Optional<Comparison> comparison, StringBuilder out) {
    var lines = new ArrayList<String>();
    report.flaky().forEach(test -> lines.add(flakyWarning(test, report.whyFlaky(test))));
    Suspects.changedBetweenRuns(comparison)
        .forEach(suspect -> lines.add(changedBetweenRunsWarning(suspect)));
    Suspects.unconfirmed(report).forEach(suspect -> lines.add(unconfirmedWarning(suspect)));
    Suspects.unrelatedCatches(report).forEach(note -> lines.add(unrelatedCatchesNote(note)));
    if (!report.staleIgnores().isEmpty()) {
      lines.add(staleIgnoresWarning(report));
    }
    RepeatedIgnores.in(report)
        .forEach(
            repeated ->
                lines.add(
                    " %s %s".formatted(style.blue("note:"), code(RepeatedIgnores.text(repeated)))));
    if (!lines.isEmpty()) {
      out.append('\n');
      lines.forEach(line -> out.append(line).append('\n'));
    }
  }

  private String staleIgnoresWarning(Report report) {
    var ids = report.staleIgnores().stream().map(io.mutastic.api.Ignore::id).toList();
    return " %s %s: %s code changed or is gone, so %s mutant%s ran; remove with `%s`"
        .formatted(
            style.amber(
                ids.size() == 1 ? "! an ignore no longer holds:" : "! ignores no longer hold:"),
            String.join(", ", ids),
            ids.size() == 1 ? "its" : "their",
            ids.size() == 1 ? "its" : "their",
            ids.size() == 1 ? "" : "s",
            sources.invocation().command(PRUNE));
  }

  /// `ignore --prune`: what removes the ignores that no longer hold.
  static final List<String> PRUNE = List.of("ignore", "--prune");

  private String flakyWarning(String test, Optional<io.mutastic.api.FlakyTest> why) {
    if (why.filter(io.mutastic.api.FlakyTest::failsAlone).isPresent()) {
      return (" %s %s passes among the others but fails on its own, with no mutant in (run again"
              + " after the mutants, it failed%s); its catches don't count, the mutants it reached"
              + " ran again without it, and the run can't pass until it passes on its own")
          .formatted(
              style.red("! flaky:"),
              AgentText.shortName(test),
              why.get().failedWith().map(" with %s"::formatted).orElse(""));
    }
    return " %s %s failed, then passed when run again; left out of this run%s%s"
        .formatted(
            style.amber("! flaky:"),
            AgentText.whatFailed(test),
            test.contains("#") ? "" : " with every test in it",
            flakyReason(why));
  }

  /// `; it failed with HttpTimeoutException, from outside the code`, or what it reaches that
  /// changes from run to run.
  private static String flakyReason(Optional<io.mutastic.api.FlakyTest> why) {
    if (why.isEmpty()) {
      return "";
    }
    if (!why.get().reachesInTest().isEmpty()) {
      return "; its own code reaches %s".formatted(String.join(", ", why.get().reachesInTest()));
    }
    var outside = why.get().outsideCause();
    if (why.get().failedOutside() && outside.isPresent()) {
      return "; it failed with %s, from outside the code".formatted(outside.get());
    }
    return why.get().reaches().isEmpty()
        ? ""
        : "; it reaches %s".formatted(String.join(", ", why.get().reaches()));
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
      out.append(style.dim(" Next: " + MutantCommand.next(sources.invocation()))).append('\n');
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
    if (!run.leftOut().isEmpty()) {
      rows.put("Left out", String.join(", ", run.leftOut()) + style.dim(" (--exclude-tests)"));
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
    putRunsAnyway(rows, report);
    var worst = worstMethods(report);
    if (!worst.isEmpty()) {
      rows.put("Worst at", worst);
    }
    if (!report.flaky().isEmpty()) {
      rows.put("Flaky", flakyRow(report));
    }
  }

  /// What a run ran beyond its mutants: an audit of the arid filters, and ghosts.
  private static void putRunsAnyway(Map<String, String> rows, Report report) {
    var audit = AridAudit.of(report);
    if (audit.happened()) {
      rows.put("Arid audit", audit.summary());
    }
    if (GhostCards.lookedFor(report)) {
      rows.put("Ghosts", GhostCards.summary(report));
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
                " %s  %s\n"
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
        + style.dim("  threshold " + threshold)
        + coveredScore(report);
  }

  /// ` · 74.1% where tests reach`, when some code is out of their reach: the score of what they
  /// run.
  private String coveredScore(Report report) {
    var covered = report.coveredScore();
    return report.count(Status.NOT_COVERED) == 0 || covered.isEmpty()
        ? ""
        : style.dim("  · %.1f%% where tests reach".formatted(covered.getAsDouble()));
  }

  /// The last line: each mutant's fate when they were asked for by id, or PASS or FAIL with why.
  private String verdict(Report report, Run run) {
    long missed = report.count(Status.MISSED);
    if (run.oneMutant() && !report.results().isEmpty()) {
      return fates(report);
    }
    if (report.score().isEmpty()) {
      return " %s %s\n"
          .formatted(
              style.label("PASS", Style.GREEN),
              style.dim("Nothing to measure: no mutants to score."));
    }
    if (!report.failingAlone().isEmpty()) {
      return failedAlone();
    }
    if (report.passes(threshold)) {
      return " %s %s\n"
          .formatted(
              style.label("PASS", Style.GREEN),
              style.green(
                  missed == 0
                      ? words.allDetected
                      : "%d %s, above the threshold.".formatted(missed, words.undetected)));
    }
    return failed(missed, report.count(Status.NOT_COVERED));
  }

  /// Each mutant's fate, one a line, when they were asked for by id.
  private String fates(Report report) {
    return report.results().stream()
        .map(result -> " %s\n".formatted(fate(result)))
        .collect(Collectors.joining());
  }

  /// FAIL, whatever the score: a test that caught mutants fails on its own.
  private String failedAlone() {
    return " %s %s\n"
        .formatted(
            style.label("FAIL", Style.RED),
            style.red(
                "A test that caught mutants fails on its own, with no mutant in, so its catches"
                    + " don't count: see the flaky tests above."));
  }

  private String failed(long missed, long notCovered) {
    return " %s %s\n"
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
      case MISSED -> missedFate(id);
      case NOT_COVERED -> redFate(words.notCovered, "%s: no test reaches its line".formatted(id));
      case SKIPPED ->
          style.label("SKIPPED", Style.CYAN)
              + " "
              + "%s is skipped: %s".formatted(id, result.mutant().skipped().orElse(""));
    };
  }

  /// `m855c8e3c is still missed · done when mutastic verify m855c8e3c passes`.
  private String missedFate(String id) {
    var doneWhen = MutantCommand.DONE_WHEN.text(id, sources.invocation());
    return redFate(
        words.undetected,
        "%s is still %s · done when %s".formatted(id, words.undetected, doneWhen));
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

  /// `2 skipped (1 equivalent, 1 ignored as unreachable)`: the kind is each reason's first word,
  /// and an ignore's the sort it was given.
  private static String skippedByKind(Report report) {
    var byKind = new TreeMap<String, Long>();
    report.results().stream()
        .filter(result -> result.status() == Status.SKIPPED)
        .forEach(
            result ->
                byKind.merge(kind(result.mutant().skipped().orElse("other: ?")), 1L, Long::sum));
    return "%d skipped (%s)"
        .formatted(
            report.count(Status.SKIPPED),
            byKind.entrySet().stream()
                .map(entry -> entry.getValue() + " " + entry.getKey())
                .collect(Collectors.joining(", ")));
  }

  /// `equivalent` for `equivalent: …`; `ignored as unreachable` for `user: unreachable: …
  /// (ignored)`.
  private static String kind(String reason) {
    var first = reason.substring(0, Math.max(0, reason.indexOf(':')));
    if (!reason.endsWith("(ignored)")) {
      return first;
    }
    var rest = reason.substring(first.length() + 1).strip();
    return io.mutastic.api.Ignore.KINDS.stream()
        .filter(kind -> rest.startsWith(kind + ":"))
        .findFirst()
        .map(kind -> "ignored as " + kind)
        .orElse("ignored");
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

  /// `Shipping.Zone.surcharge()`, or `Shipping.heavyOnes() (a lambda)`: where the misses are, as
  /// the source names it.
  private static String methodLabel(Mutant mutant) {
    var where = SourceNames.of(mutant);
    var member = new ClassMutator.Member(mutant.methodName(), mutant.descriptor());
    if (mutant.operator() instanceof ClassMutator && !member.isMethod()) {
      return member.isClass() ? where.className() : where.className() + "." + member.simpleName();
    }
    if (where.method().isEmpty()) {
      return where.className() + where.kind().map(kind -> " (" + plain(kind) + ")").orElse("");
    }
    return where.className()
        + "."
        + where.method()
        + "()"
        + where.kind().map(kind -> " (" + plain(kind) + ")").orElse("");
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
