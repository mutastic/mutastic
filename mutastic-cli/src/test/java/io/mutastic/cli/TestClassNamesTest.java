package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A test class in a package production code also uses runs by its own name, as in the build, in
/// the `test-class-names` sample: a test class in another package that holds one of its type, and a
/// test that reads its nested class's name, both pass with no mutant in and catch what they reach.
/// A renamed copy each run (`BinderTest__m2`) once failed them both before any mutant (A4).
class TestClassNamesTest {
  private static final Path SAMPLE =
      Path.of(System.getProperty("mutastic.samples"), "test-class-names");

  @TempDir Path work;

  @Test
  void testsThatReachATestClassByItsNamePassAsInTheBuild() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("test-class-names"));

    var run = Samples.mutastic("run", project.toString(), "--format=agent", "--report=none");

    assertThat(run.exit()).isZero();
    assertThat(run.out())
        .startsWith("mutastic: PASS · score 100.0%")
        .doesNotContain("fails without any mutant")
        .doesNotContain("__m");
  }
}
