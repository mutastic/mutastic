package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A run on a module another process's run holds waits for it, says so, and then gets the whole
/// result; a second run in the same JVM doesn't wait on the first.
class WorkLockTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");

  @TempDir Path work;

  @Test
  void aRunWaitsForAnotherProcesssRunOnTheModule() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    var holder =
        new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                LockHolder.class.getName(),
                project.resolve("build/mutastic").toString())
            .redirectErrorStream(true)
            .start();
    try (var said =
        new BufferedReader(
            new InputStreamReader(holder.getInputStream(), StandardCharsets.UTF_8))) {
      assertThat(said.readLine()).isEqualTo("held true");
      var run =
          CompletableFuture.supplyAsync(
              () -> Samples.mutastic("run", project.toString(), "--format=jsonl"));
      Thread.sleep(2000);
      assertThat(run).as("it waits while the other run holds the module").isNotDone();
      holder.getOutputStream().close();
      var result = run.get(5, TimeUnit.MINUTES);

      assertThat(result.err())
          .contains("mutastic: waiting for another mutastic run on this module");
      assertThat(result.out()).contains("\"reason\":\"run-finished\"");
    } finally {
      holder.destroy();
    }
  }

  @Test
  void aRunInsideAnotherInTheSameJvmDoesntWaitOnIt() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    var module = project.resolve("build/mutastic");
    var outer = WorkLock.hold(module, System.err);
    try {
      var inner = Samples.mutastic("run", project.toString(), "--format=jsonl");
      assertThat(inner.err()).doesNotContain("waiting");
      assertThat(inner.out()).contains("\"reason\":\"run-finished\"");
    } finally {
      outer.close();
    }
  }
}
