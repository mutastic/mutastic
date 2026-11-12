package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// When there are no tests to run, when the tests measure nothing, or the worker running them
/// stops: a module with code and no tests (an admin server) is every mutant not covered, the honest
/// score, rather than a worker that can't start; tests that pass and reach nothing stop the run; a
/// worker that stops is reported at once.
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

  /// Tests that ran and passed while the coverage pass recorded nothing measured nothing: the run
  /// stops and says so, rather than scoring every mutant not covered. Seen with a coverage agent
  /// that kept mutastic's probes out of a Quarkus project; a suite that never calls the code is the
  /// same evidence, and the honest way to get it here.
  @Test
  void testsThatPassAndReachNothingStopTheRun() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    delete(project.resolve("src/test"));
    Samples.writeTestReachingNothing(project);

    var run = Samples.mutastic("run", project.toString(), "--format=brief", "--report=none");

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .contains("1 test ran and passed in pricing, but the coverage pass recorded no line")
        .contains("here the tests' JVM arguments load none")
        .doesNotContain("internal error");
    assertThat(run.out()).doesNotContain("Score");
  }

  /// A run of only some mutants may well be of a class no test reaches: there, not covered is the
  /// honest answer, and it's given.
  @Test
  void aRunOfSomeMutantsReachingNothingIsScored() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    delete(project.resolve("src/test"));
    Samples.writeTestReachingNothing(project);

    var run =
        Samples.mutastic(
            "run", project.toString(), "--shard=1/2", "--format=brief", "--report=none");

    assertThat(run.exit()).isEqualTo(Main.FAILED);
    assertThat(run.out()).contains("     Score  0.0%");
  }

  /// A test that ends its JVM (`System.exit`, a native crash) takes the worker with it: the run
  /// says so at once. Before, it waited out the coverage pass's 10-minute limit and then called it
  /// a timeout, so the message alone shows which happened.
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

    var run = Samples.mutastic("run", project.toString(), "--format=brief", "--report=none");

    assertThat(run.exit()).isEqualTo(Main.INTERNAL);
    assertThat(run.err()).contains("the test worker stopped during the coverage pass");
  }

  private static void delete(Path directory) throws IOException {
    try (Stream<Path> paths = Files.walk(directory)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }
}
