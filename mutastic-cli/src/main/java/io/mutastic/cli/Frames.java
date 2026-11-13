package io.mutastic.cli;

import io.mutastic.api.Status;
import io.mutastic.report.HumanText;
import java.time.Duration;

/// The words progress prints, as text: a pure function of what a phase has done, how long it has
/// taken and, on a terminal, the spinner's frame. `PROGRESS.md` is the contract, shared with the
/// other edition byte for byte.
final class Frames {
  private Frames() {}

  /// One frame per redraw.
  static final String SPINNER = "⠋⠙⠹⠸⠼⠴⠦⠧⠇⠏";

  /// The longest build tool line shown under the live line, so the block stays two lines at 80
  /// columns.
  static final int BELOW = 74;

  private static final String BOLD = "\u001b[1m";
  private static final String DIM = "\u001b[2m";
  private static final String RESET = "\u001b[0m";

  /// The live block on a terminal: the spinner, the phase in bold, what it's doing and its clock;
  /// then, dim, the build tool's latest line. No newline at its end.
  static String live(Snapshot snapshot, Duration elapsed, int frame, boolean colour) {
    var word = snapshot.phase().word();
    var line =
        SPINNER.charAt(Math.floorMod(frame, SPINNER.length()))
            + " "
            + styled(BOLD, word, colour)
            + snapshot.phase().padded().substring(word.length())
            + fields(snapshot)
            + " · "
            + clock(elapsed);
    return snapshot
        .below()
        .map(below -> line + "\n" + styled(DIM, "   └ " + shortened(below), colour))
        .orElse(line);
  }

  /// The same, where nothing is drawn in place: the live line's fields in their short form, plain,
  /// with a newline. A count with a total is in brackets, and checking's has no step's words.
  static String heartbeat(Snapshot snapshot, Duration elapsed) {
    return snapshot.phase().padded() + shortFields(snapshot) + " · " + clock(elapsed) + "\n";
  }

  /// The live line's fields, short: `[18/181 tests]`, `52 missed · 18/52`.
  private static String shortFields(Snapshot snapshot) {
    return switch (snapshot.phase()) {
      case COVERAGE ->
          snapshot.total() == 0
              ? "listing the tests"
              : "[" + snapshot.done() + "/" + snapshot.total() + " tests]";
      case CHECKING ->
          snapshot.count(Status.MISSED)
              + " missed"
              + (snapshot.total() == 0 ? "" : " · " + snapshot.done() + "/" + snapshot.total());
      case READING, BUILDING, MUTANTS -> fields(snapshot);
    };
  }

  /// What a phase is doing and how far it is, without its clock.
  static String fields(Snapshot snapshot) {
    return switch (snapshot.phase()) {
      case READING, BUILDING -> snapshot.doing();
      case COVERAGE ->
          snapshot.total() == 0
              ? "listing the tests"
              : snapshot.done() + "/" + snapshot.total() + " tests";
      case MUTANTS -> "[%d/%d] %s".formatted(snapshot.done(), snapshot.total(), counts(snapshot));
      case CHECKING ->
          snapshot.count(Status.MISSED)
              + " missed · "
              + snapshot.doing()
              + (snapshot.total() == 0 ? "" : ", " + snapshot.done() + "/" + snapshot.total());
    };
  }

  /// `✓ 23 caught · ✗ 8 missed · ○ 9 not covered · ⏱ 0 timed out`.
  static String counts(Snapshot snapshot) {
    return "✓ %d caught · ✗ %d missed · ○ %d not covered · ⏱ %d timed out"
        .formatted(
            snapshot.count(Status.CAUGHT),
            snapshot.count(Status.MISSED),
            snapshot.count(Status.NOT_COVERED),
            snapshot.count(Status.TIMED_OUT));
  }

  /// `built 55 mutants in 3.5s`: the build, and the mutants found in what it built.
  static String built(int mutants, Duration took) {
    return Phase.padded("built") + mutants(mutants) + " in " + duration(took) + "\n";
  }

  /// `ran 55 mutants in 7.0s · ✓ 23 caught · …`: every mutant has its first result.
  static String ran(Snapshot snapshot, Duration took) {
    return Phase.padded("ran")
        + mutants(snapshot.done())
        + " in "
        + duration(took)
        + " · "
        + counts(snapshot)
        + "\n";
  }

  /// `checked 8 missed mutants in 3.1s · a test to write for 5`.
  static String checked(int missed, int suggested, Duration took) {
    return Phase.padded("checked")
        + missed
        + (missed == 1 ? " missed mutant" : " missed mutants")
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

  /// How long a phase took, as the summary says it: `840ms`, `3.5s`, `51.0s`, `9m41s`, `62m05s`.
  static String duration(Duration took) {
    return HumanText.seconds(took);
  }

  private static String mutants(int count) {
    return count + (count == 1 ? " mutant" : " mutants");
  }

  private static String styled(String style, String text, boolean colour) {
    return colour ? style + text + RESET : text;
  }

  /// A line cut to [#BELOW] characters, an ellipsis saying so.
  private static String shortened(String line) {
    return line.length() <= BELOW ? line : line.substring(0, BELOW - 1) + "…";
  }
}
