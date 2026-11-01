package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// When there are no tests to run, or the worker running them stops: a module with code and no
/// tests (an admin server) is every mutant not covered, the honest score, rather than a worker that
/// can't start; a worker that stops is reported at once.
class NothingRunsTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");

  @TempDir Path work;

  @Test
  void everyMutantIsNotCovered() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    delete(project.resolve("src/test"));

    var run = Samples.mutastic("run", project.toString(), "--format=brief", "--report=none");

    assertThat(run.exit()).isEqualTo(Main.FAILED);
    assertThat(run.out())
        .contains("     Tests  none found (mutastic runs JUnit Platform tests)")
        .contains("     Score  0.0%")
        .doesNotContain("internal error");
  }

  /// A test that ends its JVM (`System.exit`, a native crash) takes the worker with it: the run
  /// says so at once, rather than waiting out the coverage pass's limit.
  @Test
  void aWorkerThatStopsIsReportedAtOnce() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    Files.writeString(
        project.resolve("src/test/java/com/example/pricing/HaltsTest.java"),
        """
        package com.example.pricing;

        import org.junit.jupiter.api.Test;

        class HaltsTest {
          @Test
          void halts() {
            Runtime.getRuntime().halt(3);
          }
        }
        """);
    long start = System.nanoTime();

    var run = Samples.mutastic("run", project.toString(), "--format=brief", "--report=none");

    assertThat(run.exit()).isEqualTo(Main.INTERNAL);
    assertThat(run.err()).contains("the test worker stopped during the coverage pass");
    assertThat(java.time.Duration.ofNanos(System.nanoTime() - start))
        .isLessThan(java.time.Duration.ofMinutes(1));
  }

  private static void delete(Path directory) throws IOException {
    try (Stream<Path> paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }
}
