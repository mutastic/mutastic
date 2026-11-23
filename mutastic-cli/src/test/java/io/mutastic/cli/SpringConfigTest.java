package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `samples/spring-config`: the tests' Spring Boot configuration, `TestApp`, is in the app's own
/// package and scans it, so the scan finds `TestApp` as well as the tests naming it. A test in that
/// package runs as a renamed copy each run, and when its references to `TestApp` were renamed too,
/// Spring had two configurations, each declaring the clock, and refused the second: every test
/// failed before any mutant ran.
class SpringConfigTest {
  private static final Path SAMPLE =
      Path.of(System.getProperty("mutastic.samples"), "spring-config");

  @TempDir Path work;

  @Test
  void aConfigurationTheScanFindsIsTheOneTheTestsName() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("spring-config"));

    var run = Samples.mutastic("run", project.toString(), "--format=agent", "--report=none");

    assertThat(run.err()).doesNotContain("fail without any mutant");
    assertThat(run.out())
        .as("the run:%n%s%s", run.out(), run.err())
        .contains("2 missed, 0 not covered, 12 caught of 14 mutants")
        .contains("no test checks `isOpen()` returning `false` here");
  }
}
