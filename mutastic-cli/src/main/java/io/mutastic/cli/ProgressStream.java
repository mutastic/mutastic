package io.mutastic.cli;

import java.io.PrintStream;
import java.time.Duration;
import java.util.List;
import java.util.function.LongSupplier;

/// Progress's stderr: permanent lines, and under them a live block redrawn in place, or, where
/// nothing is drawn in place, a heartbeat once the last line is old enough. Each frame (the block
/// cleared, any permanent lines, the block again) is one write, so a terminal never shows the block
/// half gone. Not thread-safe: its caller holds one lock around every call, so a partial line never
/// meets a permanent one.
final class ProgressStream {
  /// Wrapping off while the block is drawn, so a line the terminal narrowed under is cut rather
  /// than wrapped onto a line the next redraw wouldn't clear; and on again after it.
  static final String NO_WRAP = "\u001b[?7l";

  static final String WRAP = "\u001b[?7h";

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

  /// A permanent line, or lines, each ending with a newline, in place of the live block, if one is
  /// drawn, and `block` under it.
  void line(String text, List<String> block) {
    send(clearing() + text + drawing(block));
    lastLine = nanos.getAsLong();
  }

  /// The live block, drawn over the last.
  void draw(List<String> block) {
    send(clearing() + drawing(block));
  }

  /// The live block gone, the cursor at the start of the line it began on.
  void clear() {
    send(clearing());
  }

  /// Whether the last line is as old as a heartbeat.
  boolean heartbeatDue() {
    return nanos.getAsLong() - lastLine >= heartbeat;
  }

  private void send(String frame) {
    if (!frame.isEmpty()) {
      err.print(frame);
      err.flush();
    }
  }

  /// What clears the live block: its last line, then each line above it.
  private String clearing() {
    var clearing = drawn == 0 ? "" : "\r\u001b[2K" + "\u001b[1A\u001b[2K".repeat(drawn - 1);
    drawn = 0;
    return clearing;
  }

  /// What draws `block`, its lines joined, the cursor left at the end of its last.
  private String drawing(List<String> block) {
    drawn = block.size();
    return block.isEmpty() ? "" : NO_WRAP + String.join("\n", block) + WRAP;
  }
}
