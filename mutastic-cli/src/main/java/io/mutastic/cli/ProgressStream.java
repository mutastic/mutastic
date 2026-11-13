package io.mutastic.cli;

import java.io.PrintStream;
import java.time.Duration;
import java.util.function.LongSupplier;

/// Progress's stderr: permanent lines, and under them a live block redrawn in place, or, where
/// nothing is drawn in place, a heartbeat once the last line is old enough. Not thread-safe: its
/// caller holds one lock around every call, so a partial line never meets a permanent one.
final class ProgressStream {
  private final PrintStream err;
  private final LongSupplier nanos;
  private final long heartbeat;

  /// How many lines of the live block are on the screen, the cursor at the end of the last.
  private int drawn;

  private long lastLine;

  /// @param nanos the clock, [System#nanoTime] but in tests
  /// @param heartbeat how old the last line may get before a heartbeat
  ProgressStream(PrintStream err, LongSupplier nanos, Duration heartbeat) {
    this.err = err;
    this.nanos = nanos;
    this.heartbeat = heartbeat.toNanos();
    this.lastLine = nanos.getAsLong();
  }

  /// A permanent line, or lines, each ending with a newline: in place of the live block, if one is
  /// drawn.
  void line(String text) {
    err.print(clearing() + text);
    err.flush();
    drawn = 0;
    lastLine = nanos.getAsLong();
  }

  /// The live block, drawn over the last.
  void draw(String block) {
    err.print(clearing() + block);
    err.flush();
    drawn = 1 + (int) block.chars().filter(character -> character == '\n').count();
  }

  /// The live block gone, the cursor at the start of the line it began on.
  void clear() {
    if (drawn > 0) {
      err.print(clearing());
      err.flush();
      drawn = 0;
    }
  }

  /// Whether the last line is as old as a heartbeat.
  boolean heartbeatDue() {
    return nanos.getAsLong() - lastLine >= heartbeat;
  }

  /// What clears the live block: its last line, then each line above it.
  private String clearing() {
    return drawn == 0 ? "" : "\r\u001b[2K" + "\u001b[1A\u001b[2K".repeat(drawn - 1);
  }
}
