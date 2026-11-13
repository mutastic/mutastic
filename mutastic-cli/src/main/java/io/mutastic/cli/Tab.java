package io.mutastic.cli;

import io.mutastic.api.Status;
import java.util.OptionalInt;
import java.util.concurrent.atomic.AtomicBoolean;

/// The run's progress in the terminal's tab or taskbar, as OSC 9;4 draws it: busy while a phase has
/// no percent, the mutants' weighted percent while they run, and red from the moment the score
/// falls below the threshold, once twenty mutants have a score, and red it stays. A miss alone
/// never turns it red. Every way out clears it. See `PROGRESS.md`.
final class Tab {
  /// No progress in the tab (state 0).
  static final String CLEARED = "\u001b]9;4;0\u001b\\";

  /// Busy, with no percent (state 3).
  static final String BUSY = "\u001b]9;4;3;0\u001b\\";

  /// How many mutants must have a score before it can turn the tab red.
  static final int SCORED_ENOUGH = 20;

  private OptionalInt threshold = OptionalInt.empty();
  private boolean red;

  /// Whether the tab shows progress now, for the ways out that must clear it.
  private final AtomicBoolean shown = new AtomicBoolean();

  /// From now on, red below `score`; never, unless this is called, as with `--fail-on-new`.
  void redBelow(int score) {
    threshold = OptionalInt.of(score);
  }

  /// What the tab shows at this moment of the run.
  String now(Snapshot snapshot) {
    shown.set(true);
    if (snapshot.phase() != Phase.MUTANTS) {
      return BUSY;
    }
    red |= below(snapshot);
    var percent = (long) Math.floor(snapshot.estimate().weighted().orElse(0.0) * 100);
    return "\u001b]9;4;" + (red ? 2 : 1) + ";" + percent + "\u001b\\";
  }

  /// Whether the score of the mutants run so far is below the threshold, once enough have one.
  private boolean below(Snapshot snapshot) {
    int detected = snapshot.count(Status.CAUGHT) + snapshot.count(Status.TIMED_OUT);
    int scored = detected + snapshot.count(Status.MISSED) + snapshot.count(Status.NOT_COVERED);
    return threshold.isPresent()
        && scored >= SCORED_ENOUGH
        && 100.0 * detected / scored < threshold.getAsInt();
  }

  /// What clears the tab, if it shows progress: once.
  String cleared() {
    return shown.getAndSet(false) ? CLEARED : "";
  }
}
