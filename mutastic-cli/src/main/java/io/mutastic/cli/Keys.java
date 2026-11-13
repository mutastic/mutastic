package io.mutastic.cli;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/// The `t` key, read from the terminal a key at a time and not echoed while a run lasts. Java can't
/// set a terminal's mode, so `stty` does, on the terminal that's stdin, and every way out sets it
/// back ([WaysOut]). Ctrl-C and Ctrl-Z still work: only line editing and echo are off.
final class Keys implements AutoCloseable {
  /// A read returns after a tenth of a second with nothing, so the reader can stop.
  private static final List<String> ONE_AT_A_TIME =
      List.of("-icanon", "-echo", "min", "0", "time", "1");

  private static final Duration NOTHING_READ = Duration.ofMillis(50);

  /// The terminal's settings before the run took it, as `stty -g` says them.
  private final String before;

  private final AtomicBoolean stopped = new AtomicBoolean();
  private final Thread reader;
  private final Runnable forget;

  private Keys(String before, Runnable asked) {
    this.before = before;
    this.forget = WaysOut.add(this::giveBack, Keys::take);
    var in = new FileInputStream(FileDescriptor.in);
    this.reader = Thread.ofPlatform().name("mutastic-keys").daemon().start(() -> read(in, asked));
  }

  /// Listens for `t` when stdin is a terminal `stty` can set, calling `asked` for each.
  static Optional<Keys> listen(Runnable asked) {
    var before = stty(List.of("-g")).map(String::strip).filter(said -> !said.isEmpty());
    if (before.isEmpty() || !take()) {
      return Optional.empty();
    }
    return Optional.of(new Keys(before.get(), asked));
  }

  private void read(InputStream in, Runnable asked) {
    try {
      while (!stopped.get()) {
        int key = in.read();
        if (key == 't') {
          asked.run();
        } else if (key < 0) {
          Thread.sleep(NOTHING_READ);
        }
      }
    } catch (IOException e) {
      // the terminal has gone: nothing more to hear
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  /// The terminal reading a key at a time, unechoed: when the run starts, and when it continues
  /// after Ctrl-Z.
  private static boolean take() {
    return stty(ONE_AT_A_TIME).isPresent();
  }

  /// The terminal as the run found it.
  private void giveBack() {
    stty(List.of(before));
  }

  /// Stops reading before the terminal goes back to lines, so no key typed after the run is taken.
  @Override
  public void close() {
    stopped.set(true);
    try {
      reader.join(Duration.ofMillis(500));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    forget.run();
    giveBack();
  }

  /// What `stty` says, run on stdin's terminal; empty when it fails, as it does on anything else.
  private static Optional<String> stty(List<String> arguments) {
    var command = new java.util.ArrayList<>(List.of("stty"));
    command.addAll(arguments);
    try {
      var process =
          new ProcessBuilder(command)
              .redirectInput(ProcessBuilder.Redirect.INHERIT)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      var said = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      return process.waitFor() == 0 ? Optional.of(said) : Optional.empty();
    } catch (IOException e) {
      return Optional.empty();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }
}
