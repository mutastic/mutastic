package io.mutastic.cli;

import io.mutastic.engine.Listener;
import io.mutastic.engine.MutantResult;
import io.mutastic.engine.Status;
import java.io.PrintStream;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/// What's happening, on stderr, so stdout stays the result. On a terminal one line is rewritten
/// in place; elsewhere (CI logs, pipes) a counter line is appended every [#EVERY], never an
/// animation.
final class Progress implements Listener {
  enum Mode { BAR, COUNTER, NONE }

  static final Duration EVERY = Duration.ofSeconds(10);

  private final PrintStream err;
  private final Mode mode;
  private final Map<Status, Integer> counts = new EnumMap<>(Status.class);
  private final long start = System.nanoTime();
  private long lastPrinted = System.nanoTime();
  private int total;
  private int done;

  Progress(PrintStream err, Mode mode) {
    this.err = err;
    this.mode = mode;
  }

  /// The first line, before anything slow: within 100 ms of starting.
  void started(String version) {
    if (mode != Mode.NONE) {
      err.printf("mutastic %s · reading the Gradle build…%n", version);
    }
  }

  @Override
  public void coverageFinished(int jdk, int tests, Duration took, int mutants, List<String> flaky) {
    total = mutants;
    if (mode != Mode.NONE) {
      err.printf("coverage  %d tests on JDK %d in %s%s · %d mutants%n", tests, jdk, io.mutastic.report.HumanText.seconds(took),
          flaky.isEmpty() ? "" : " (%d flaky, left out)".formatted(flaky.size()), mutants);
    }
  }

  @Override
  public void mutantFinished(MutantResult result) {
    done++;
    counts.merge(result.status(), 1, Integer::sum);
    switch (mode) {
      case BAR -> err.print("\r\u001b[2K" + counter());
      case COUNTER -> {
        if (System.nanoTime() - lastPrinted >= EVERY.toNanos() || done == total) {
          err.println(counter());
          lastPrinted = System.nanoTime();
        }
      }
      case NONE -> {}
    }
  }

  /// Clears the in-place line, so the result starts on a clean one.
  void finished() {
    if (mode == Mode.BAR && done > 0) {
      err.print("\r\u001b[2K");
    }
    err.flush();
  }

  private String counter() {
    long elapsed = (System.nanoTime() - start) / 1_000_000_000L;
    return "[%d/%d] ✓ %d caught · ✗ %d missed · ○ %d not covered · ⏱ %d timed out  %d:%02d".formatted(done, total,
        counts.getOrDefault(Status.CAUGHT, 0), counts.getOrDefault(Status.MISSED, 0),
        counts.getOrDefault(Status.NOT_COVERED, 0), counts.getOrDefault(Status.TIMED_OUT, 0), elapsed / 60, elapsed % 60);
  }
}
