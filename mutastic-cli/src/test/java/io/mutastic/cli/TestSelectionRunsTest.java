package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// mutastic runs the tests the build runs, with the environment it gives them: a tag or a pattern
/// the build leaves out can't catch a mutant, and a test that needs the build's environment passes.
/// The same project for each build tool: only an excluded test checks `fee(10)`.
class TestSelectionRunsTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));

  @TempDir Path work;

  @Test
  void gradlesTestTaskDecides() throws Exception {
    assertTheBoundaryIsMissed(
        Samples.copy(SAMPLES.resolve("selection"), work.resolve("selection")));
  }

  @Test
  void surefireDecides() throws Exception {
    assertTheBoundaryIsMissed(
        Samples.copy(SAMPLES.resolve("maven-selection"), work.resolve("maven-selection")));
  }

  private static void assertTheBoundaryIsMissed(Path project) {
    var run = Samples.mutastic("run", project.toString(), "--format=jsonl", "--report=none");

    assertThat(run.err()).doesNotContain("without any mutant");
    assertThat(run.out())
        .contains("\"tests\":1,")
        .containsPattern("\"status\":\"Survived\",\"operator\":\"mutastic/boundary\"");
  }
}
