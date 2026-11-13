package io.mutastic.cli;

import io.mutastic.api.Status;
import io.mutastic.cli.Snapshot.Worker;
import io.mutastic.report.HumanText;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/// The words progress prints, as text: a pure function of what a phase has done, its clock, the
/// spinner's frame and the terminal's width. `PROGRESS.md` is the contract, shared with the other
/// edition byte for byte.
final class Frames {
  private Frames() {}

  /// One frame per redraw.
  static final String SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";

  private static final String GREEN = "32";
  private static final String RED = "1;31";
  private static final String AMBER = "33";
  private static final String BOLD = "1";
  private static final String DIM = "2";
  private static final String SLOW = "1;33";
  private static final String HEADING = "1;34";
  private static final String ID = "36";

  /// The spinner, a space and the phase's column: what comes before a live line's fields.
  private static final int LEAD = 2 + Phase.COLUMN;

  /// The bar at its widest, as it first shrinks, and as it last does once the fates have no words.
  private static final int WIDEST_BAR = 40;

  private static final int NARROW_BAR = 12;
  private static final int NARROWEST_BAR = 6;

  /// How many fates' forms there are, from all their words to none.
  private static final int TIERS = 3;

  /// Below this many columns, four workers' lines show rather than eight.
  private static final int WIDE = 100;

  /// The live block on a terminal: the phase's line with its spinner, the phase in bold, a bar,
  /// what it's done and its clock; then, dim, the build tool's latest line, or a line for each
  /// worker. Each line fits `columns`.
  static List<String> live(Snapshot snapshot, int frame, int columns, boolean colour) {
    var lines = new ArrayList<String>();
    var lead = lead(snapshot.phase(), frame, colour);
    lines.add(Fit.cut(lead + status(snapshot, columns, colour), columns));
    snapshot
        .below()
        .ifPresent(below -> lines.add(Fit.cut(paint(colour, DIM, "   └ " + below), columns)));
    if (showsWorkers(snapshot)) {
      lines.addAll(workerLines(snapshot, columns, colour));
    }
    return lines;
  }

  private static boolean showsWorkers(Snapshot snapshot) {
    var phase = snapshot.phase();
    return (phase == Phase.COVERAGE || phase == Phase.MUTANTS) && !snapshot.workers().isEmpty();
  }

  /// The spinner, green, and the phase's word, bold, its padding not.
  private static String lead(Phase phase, int frame, boolean colour) {
    var spinner = String.valueOf(SPINNER.charAt(Math.floorMod(frame, SPINNER.length())));
    var word = phase.word();
    return paint(colour, GREEN, spinner)
        + " "
        + paint(colour, BOLD, word)
        + phase.padded().substring(word.length());
  }

  /// What the phase has done, and its clock: as wide as `columns` lets the bar be.
  private static String status(Snapshot snapshot, int columns, boolean colour) {
    var clock = clock(snapshot.elapsed());
    return switch (snapshot.phase()) {
      case READING, BUILDING -> snapshot.doing() + " · " + clock;
      case COVERAGE ->
          snapshot.total() == 0
              ? "listing the tests · " + clock
              : coverage(snapshot, columns, colour);
      case MUTANTS -> mutants(snapshot, columns, colour);
      case CHECKING -> checking(snapshot) + " · " + clock;
    };
  }

  /// `━━━━─── 72/181 tests · 4m02s`.
  private static String coverage(Snapshot snapshot, int columns, boolean colour) {
    var said =
        "  " + snapshot.done() + "/" + snapshot.total() + " tests · " + clock(snapshot.elapsed());
    int room = Math.max(0, columns - LEAD - Fit.width(said));
    double fraction = (double) snapshot.done() / snapshot.total();
    return bar(fraction, Math.min(room, WIDEST_BAR), colour) + said;
  }

  /// `━━━─── 52% · 2871/5525 · ✓ 2610 caught · … · 24m10s · ~21m left`: the bar shrinks first as
  /// the terminal narrows, then the fates lose their words.
  private static String mutants(Snapshot snapshot, int columns, boolean colour) {
    var estimate = snapshot.estimate();
    var percent = estimate.weighted().map(weighted -> percent(weighted) + " · ").orElse("");
    var head = "  " + percent + snapshot.done() + "/" + snapshot.total() + " · ";
    var tail =
        " · "
            + clock(snapshot.elapsed())
            + estimate.left().map(left -> " · " + left(left)).orElse("");
    for (int tier = 0; tier < TIERS; tier++) {
      var said = fates(snapshot, colour, tier);
      int room = columns - LEAD - Fit.width(head) - Fit.width(said) - Fit.width(tail);
      if (room >= (tier == TIERS - 1 ? NARROWEST_BAR : NARROW_BAR)) {
        return bar(fraction(snapshot), Math.min(room, WIDEST_BAR), colour) + head + said + tail;
      }
    }
    return head.stripLeading() + fates(snapshot, colour, TIERS - 1) + tail;
  }

  /// How far the mutants are: by what each should take, or else by their count.
  private static double fraction(Snapshot snapshot) {
    return snapshot
        .estimate()
        .weighted()
        .orElse((double) snapshot.done() / Math.max(1, snapshot.total()));
  }

  /// `52%`: whole percents, never rounded up to what isn't done.
  private static String percent(double weighted) {
    return (long) Math.floor(weighted * 100) + "%";
  }

  /// `41 missed · running each catching test on the unmutated code, 18/52`.
  private static String checking(Snapshot snapshot) {
    return snapshot.count(Status.MISSED)
        + " missed · "
        + snapshot.doing()
        + (snapshot.total() == 0 ? "" : ", " + snapshot.done() + "/" + snapshot.total());
  }

  /// A bar `columns` wide, green `━` filled to `fraction` on dim `─`.
  private static String bar(double fraction, int columns, boolean colour) {
    var full = (int) Math.min(columns, Math.round(Math.clamp(fraction, 0.0, 1.0) * columns));
    return paint(colour, GREEN, "━".repeat(full)) + paint(colour, DIM, "─".repeat(columns - full));
  }

  /// A line for each worker, up to eight (four below 100 columns), then how many more are running
  /// and, where it's listened for, the `t` key.
  private static List<String> workerLines(Snapshot snapshot, int columns, boolean colour) {
    var workers = snapshot.workers();
    int shown = Math.min(workers.size(), columns >= WIDE ? 8 : 4);
    var lines = new ArrayList<String>();
    for (int at = 0; at < shown; at++) {
      lines.add(Fit.cut(workerLine(at + 1, workers.get(at), colour), columns));
    }
    long more = workers.stream().skip(shown).filter(Optional::isPresent).count();
    var footer = new ArrayList<String>();
    if (more > 0) {
      footer.add("… and " + more + " more running");
    }
    if (snapshot.keys()) {
      footer.add("t for what’s running");
    }
    if (!footer.isEmpty()) {
      lines.add(Fit.cut(paint(colour, DIM, "     " + String.join(" · ", footer)), columns));
    }
    return lines;
  }

  /// ` 7 [00:02:31] SLOW Pricing.java:17 discount() >= becomes >`, or ` 8 idle`.
  private static String workerLine(int number, Optional<Worker> running, boolean colour) {
    return running
        .map(
            worker ->
                numbered(number)
                    + " "
                    + paint(colour, DIM, timer(worker.elapsed()))
                    + " "
                    + (worker.slow() ? paint(colour, SLOW, "SLOW ") : "     ")
                    + " "
                    + worker.doing())
        .orElseGet(() -> paint(colour, DIM, numbered(number) + " idle"));
  }

  /// A worker's number, right-aligned in four columns.
  private static String numbered(int number) {
    var digits = String.valueOf(number);
    return " ".repeat(Math.max(0, 4 - digits.length())) + digits;
  }

  /// The same, where nothing is drawn in place: the live line's fields in their short form, plain,
  /// with a newline. A count with a total is in brackets, and checking's has no step's words.
  static String heartbeat(Snapshot snapshot) {
    var clock = clock(snapshot.elapsed());
    var fields =
        switch (snapshot.phase()) {
          case READING, BUILDING -> snapshot.doing() + " · " + clock;
          case COVERAGE ->
              (snapshot.total() == 0
                      ? "listing the tests"
                      : "[" + snapshot.done() + "/" + snapshot.total() + " tests]")
                  + " · "
                  + clock;
          case MUTANTS -> mutantsBeat(snapshot);
          case CHECKING ->
              snapshot.count(Status.MISSED)
                  + " missed"
                  + (snapshot.total() == 0 ? "" : " · " + snapshot.done() + "/" + snapshot.total())
                  + " · "
                  + clock;
        };
    return snapshot.phase().padded() + fields + "\n";
  }

  /// `[312/5525] 4% · ✓ 290 caught · … · 4m30s · ~21m left`.
  private static String mutantsBeat(Snapshot snapshot) {
    var estimate = snapshot.estimate();
    return "["
        + snapshot.done()
        + "/"
        + snapshot.total()
        + "]"
        + estimate.weighted().map(weighted -> " " + percent(weighted)).orElse("")
        + " · "
        + fates(snapshot, false, 0)
        + " · "
        + clock(snapshot.elapsed())
        + estimate.left().map(left -> " · " + left(left)).orElse("");
  }

  /// What's running, for `t`, Ctrl-T or SIGUSR1: a heading, then each worker's work.
  static List<String> dump(Snapshot snapshot, boolean colour) {
    var at = clock(snapshot.elapsed());
    return switch (snapshot.phase()) {
      case READING -> List.of(heading(colour, "reading " + snapshot.doing() + " at " + at));
      case BUILDING -> buildingDump(snapshot, colour);
      case COVERAGE -> coverageDump(snapshot, colour);
      case MUTANTS -> mutantsDump(snapshot, colour);
      case CHECKING ->
          List.of(
              heading(
                  colour,
                  snapshot.doing()
                      + " at "
                      + at
                      + (snapshot.total() == 0
                          ? ""
                          : " · " + snapshot.done() + " of " + snapshot.total() + " done")));
    };
  }

  /// `running ` in bold blue, then what's said.
  private static String heading(boolean colour, String text) {
    return paint(colour, HEADING, Phase.padded("running")) + text;
  }

  private static List<String> buildingDump(Snapshot snapshot, boolean colour) {
    var lines = new ArrayList<String>();
    lines.add(heading(colour, snapshot.doing() + " at " + clock(snapshot.elapsed())));
    snapshot
        .below()
        .ifPresent(below -> lines.add(paint(colour, DIM, "          last: \"" + below + "\"")));
    return lines;
  }

  /// The tests running, each with its timer.
  private static List<String> coverageDump(Snapshot snapshot, boolean colour) {
    var running = snapshot.workers();
    long busy = running.stream().filter(Optional::isPresent).count();
    var lines = new ArrayList<String>();
    lines.add(
        heading(
            colour,
            count(busy, "test")
                + " at "
                + clock(snapshot.elapsed())
                + " · "
                + snapshot.done()
                + " of "
                + snapshot.total()
                + " done"));
    for (int at = 0; at < running.size(); at++) {
      var number = numbered(at + 1);
      running.get(at).ifPresent(test -> lines.add(testLine(number, test, colour)));
    }
    return lines;
  }

  /// ` 1 com.example.PricingTest#discount [00:00:41]`, and SLOW past a minute.
  private static String testLine(String number, Worker test, boolean colour) {
    return number
        + " "
        + test.doing()
        + "  "
        + paint(colour, DIM, timer(test.elapsed()))
        + (test.slow() ? paint(colour, SLOW, " SLOW") : "");
  }

  /// Each worker's mutant: its id, where it is, its timer against what it should take, and the
  /// tests that cover it.
  private static List<String> mutantsDump(Snapshot snapshot, boolean colour) {
    var running = snapshot.workers();
    long busy = running.stream().filter(Optional::isPresent).count();
    var lines = new ArrayList<String>();
    lines.add(heading(colour, count(busy, "worker") + " at " + clock(snapshot.elapsed())));
    for (int at = 0; at < running.size(); at++) {
      var number = numbered(at + 1);
      running
          .get(at)
          .ifPresentOrElse(
              mutant -> lines.addAll(held(number, mutant, colour)),
              () -> lines.add(paint(colour, DIM, number + " idle")));
    }
    return lines;
  }

  private static List<String> held(String number, Worker mutant, boolean colour) {
    return List.of(
        number
            + " "
            + paint(colour, ID, mutant.id())
            + "  "
            + mutant.doing()
            + "  "
            + paint(colour, DIM, timer(mutant.elapsed()))
            + (mutant.slow() ? paint(colour, SLOW, " SLOW") : "")
            + " "
            + paint(colour, DIM, "(expected " + clock(mutant.expected()) + ")"),
        paint(colour, DIM, "               covered by " + count(mutant.tests(), "test")));
  }

  /// The fates in words, `✓ 2610 caught · ✗ 41 missed · ○ 214 not covered · ⏱ 6 timed out`, or
  /// shorter where there's less room: `✓ 2610 caught · ✗ 41 missed · ○ 214 · ⏱ 6`, then `✓2610 ✗41
  /// ○214 ⏱6`.
  static String fates(Snapshot snapshot, boolean colour, int tier) {
    int caught = snapshot.count(Status.CAUGHT);
    int missed = snapshot.count(Status.MISSED);
    int uncovered = snapshot.count(Status.NOT_COVERED);
    int timedOut = snapshot.count(Status.TIMED_OUT);
    if (tier >= TIERS - 1) {
      return paint(colour, GREEN, "✓" + caught)
          + " "
          + paint(colour, RED, "✗" + missed)
          + " ○"
          + uncovered
          + " "
          + paint(colour, AMBER, "⏱" + timedOut);
    }
    var worded =
        paint(colour, GREEN, "✓ " + caught + " caught")
            + " · "
            + paint(colour, RED, "✗ " + missed + " missed")
            + " · ○ "
            + uncovered;
    return tier == 0
        ? worded + " not covered · " + paint(colour, AMBER, "⏱ " + timedOut + " timed out")
        : worded + " · " + paint(colour, AMBER, "⏱ " + timedOut);
  }

  /// `built 55 mutants in 3.5s`: the build, and the mutants found in what it built.
  static String built(int mutants, Duration took) {
    return Phase.padded("built") + count(mutants, "mutant") + " in " + duration(took) + "\n";
  }

  /// `ran 55 mutants in 7.0s · ✓ 23 caught · …`: every mutant has its first result.
  static String ran(Snapshot snapshot, Duration took, boolean colour) {
    return Phase.padded("ran")
        + count(snapshot.done(), "mutant")
        + " in "
        + duration(took)
        + " · "
        + fates(snapshot, colour, 0)
        + "\n";
  }

  /// `checked 8 missed mutants in 3.1s · a test to write for 5`.
  static String checked(int missed, int suggested, Duration took) {
    return Phase.padded("checked")
        + count(missed, "missed mutant")
        + " in "
        + duration(took)
        + " · "
        + (suggested == 0 ? "no test found to suggest" : "a test to write for " + suggested)
        + "\n";
  }

  /// A phase's clock: `38s`, `3m12s`, `1h02m`.
  static String clock(Duration elapsed) {
    long seconds = elapsed.toSeconds();
    if (seconds < 60) {
      return seconds + "s";
    }
    return seconds < 3600
        ? "%dm%02ds".formatted(seconds / 60, seconds % 60)
        : "%dh%02dm".formatted(seconds / 3600, (seconds / 60) % 60);
  }

  /// A worker's timer: `[00:03:51]`.
  static String timer(Duration elapsed) {
    long seconds = elapsed.toSeconds();
    return "[%02d:%02d:%02d]".formatted(seconds / 3600, (seconds / 60) % 60, seconds % 60);
  }

  /// What's left, steadily: whole minutes above ten, ten seconds above one, five below.
  static String left(Duration remaining) {
    double seconds = remaining.toNanos() / 1e9;
    if (seconds >= 600) {
      return "~" + Math.round(seconds / 60) + "m left";
    }
    if (seconds >= 60) {
      long tens = Math.round(seconds / 10) * 10;
      return "~%dm%02ds left".formatted(tens / 60, tens % 60);
    }
    return "~" + Math.max(5, Math.round(seconds / 5) * 5) + "s left";
  }

  /// How long a phase took, as the summary says it: `840ms`, `3.5s`, `51.0s`, `9m41s`, `62m05s`.
  static String duration(Duration took) {
    return HumanText.seconds(took);
  }

  /// `1 test`, `3 tests`.
  static String count(long number, String noun) {
    return number + " " + noun + (number == 1 ? "" : "s");
  }

  private static String paint(boolean colour, String code, String text) {
    return colour ? "\u001b[" + code + "m" + text + "\u001b[0m" : text;
  }
}
