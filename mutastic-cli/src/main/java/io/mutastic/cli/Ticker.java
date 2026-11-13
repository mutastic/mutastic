package io.mutastic.cli;

import java.time.Duration;

/// A thread that runs something every so often until closed: what moves progress's clock, so it
/// moves when nothing finishes.
final class Ticker implements AutoCloseable {
  private final Thread thread;

  private Ticker(Thread thread) {
    this.thread = thread;
  }

  /// Runs `tick` every `period`, from now until closed.
  static Ticker every(Duration period, Runnable tick) {
    var thread =
        Thread.ofPlatform()
            .name("mutastic-progress")
            .daemon()
            .start(
                () -> {
                  while (sleep(period)) {
                    tick.run();
                  }
                });
    return new Ticker(thread);
  }

  /// Whether the period passed without the ticker being closed.
  private static boolean sleep(Duration period) {
    try {
      Thread.sleep(period);
      return true;
    } catch (InterruptedException e) {
      return false;
    }
  }

  /// Stops it, and waits for a tick under way to finish.
  @Override
  public void close() {
    thread.interrupt();
    try {
      thread.join(Duration.ofSeconds(1));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
