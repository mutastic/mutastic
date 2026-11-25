package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A library that finds the project's classes by its own class loader. In the
/// `library-deserializes` sample, spring-core reads a `Name` back with a plain `ObjectInputStream`,
/// which resolves the class from the latest loader on the stack, spring-core's, as
/// spring-petclinic's `VetTests#serialization` does. The libraries were on the JDK's class path,
/// below which the worker loads the project, so the lookup never reached `Name` and the test failed
/// without any mutant in. Its other tests check what a build's class path gives a library: the
/// class path lists it, and it knows the jar it came from.
class LibraryDeserializesTest {
  private static final Path SAMPLE =
      Path.of(System.getProperty("mutastic.samples"), "library-deserializes");

  @TempDir Path work;

  @Test
  void aLibraryFindsTheProjectsClassesAndTheRunScores() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("library-deserializes"));

    var run = Samples.mutastic("run", project.toString(), "--workers=1", "--format=agent");

    assertThat(run.out().lines().findFirst())
        .hasValue(
            "mutastic: PASS · score 100.0% (threshold 80) · 0 missed, 0 not covered, 2 caught of 2"
                + " mutants (1 more skipped)");
    assertThat(run.out()).doesNotContain("without any mutant");
    assertThat(run.exit()).as(run.out() + run.err()).isZero();
  }
}
