package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A test worker that ends before it's ready is said with why, in the worker's own words, as a run
/// that can't go on: not as mutastic's internal error. Here the tests' JVM takes an option no JVM
/// knows, as a build's `jvmArgs` can give it, so the JVM refuses to start.
class WorkerStartTest {
  private static final Path PRICING = Path.of(System.getProperty("mutastic.samples"), "pricing");

  @TempDir Path work;

  @Test
  void aWorkerThatEndsBeforeItsReadySaysWhy() throws Exception {
    var project = Samples.copy(PRICING, work.resolve("pricing"));
    Files.writeString(
        project.resolve("build.gradle.kts"),
        "\ntasks.test { jvmArgs(\"-XX:+MutasticNoSuchOption\") }\n",
        StandardOpenOption.APPEND);

    var run = Samples.mutastic("run", project.toString(), "--report=none");

    assertThat(run.exit()).isEqualTo(2);
    assertThat(run.err())
        .doesNotContain("internal error")
        .contains("mutastic can't run the tests in pricing.")
        .contains("Its test worker ended with status 1 before it was ready: ")
        .contains("MutasticNoSuchOption")
        .contains("Its output is in ");
  }
}
