package io.mutastic.cli;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/// What a run must undo on every way out, so the terminal is left as it was: its input back to
/// lines. A shutdown hook covers finishing, an error, Ctrl-C and a kill; Ctrl-Z undoes it before
/// the run stops, and does it again when the run continues.
final class WaysOut {
  private WaysOut() {}

  /// What to undo, and what to do again when a stopped run continues.
  private record Way(Runnable leave, Runnable resume) {}

  private static final List<Way> WAYS = new CopyOnWriteArrayList<>();
  private static final AtomicBoolean LISTENING = new AtomicBoolean();

  /// From now on, `leave` on every way out and `resume` when a stopped run continues; until the
  /// returned runnable is run. Each may run more than once.
  static Runnable add(Runnable leave, Runnable resume) {
    if (!LISTENING.getAndSet(true)) {
      Runtime.getRuntime()
          .addShutdownHook(Thread.ofPlatform().name("mutastic-leaving").unstarted(WaysOut::leave));
      Signals.on("TSTP", WaysOut::stop);
      Signals.on("CONT", WaysOut::resume);
    }
    var way = new Way(leave, resume);
    WAYS.add(way);
    return () -> WAYS.remove(way);
  }

  private static void leave() {
    WAYS.forEach(way -> way.leave().run());
  }

  private static void resume() {
    WAYS.forEach(way -> way.resume().run());
  }

  /// Ctrl-Z: the terminal as it was, then the run stopped, as Ctrl-Z would have. Handling the
  /// signal took its own action away, so the run stops itself.
  private static void stop() {
    leave();
    var pid = String.valueOf(ProcessHandle.current().pid());
    try {
      new ProcessBuilder("kill", "-STOP", pid).start().waitFor();
    } catch (IOException e) {
      resume(); // no way to stop: go on as before
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
