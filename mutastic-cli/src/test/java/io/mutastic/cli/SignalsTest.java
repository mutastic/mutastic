package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/// `kill -USR1` asks what's running, anywhere on Unix; where the system hasn't got a signal, it's
/// handled by nothing, and that's never an error.
class SignalsTest {
  private static final boolean WINDOWS =
      System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows");

  @Test
  void aSignalTheSystemHasIsHeard() throws IOException, InterruptedException {
    var heard = new CountDownLatch(1);

    boolean handled = Signals.on("USR1", heard::countDown);

    if (WINDOWS) {
      assertThat(handled).isFalse();
      return;
    }
    assertThat(handled).isTrue();
    new ProcessBuilder("kill", "-USR1", String.valueOf(ProcessHandle.current().pid()))
        .start()
        .waitFor();
    assertThat(heard.await(10, TimeUnit.SECONDS)).isTrue();
  }

  @Test
  void aSignalTheSystemHasntGotIsHandledByNothing() {
    assertThat(Signals.on("NOSUCHSIGNAL", () -> {})).isFalse();
  }
}
