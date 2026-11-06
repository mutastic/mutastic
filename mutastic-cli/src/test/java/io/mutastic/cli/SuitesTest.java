package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `--suite`, on `samples/suites` (Gradle, an `integrationTest` task and source set) and
/// `samples/maven-suites` (Failsafe): the unit tests miss a boundary the integration tests check.
class SuitesTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));

  @TempDir Path work;

  @Test
  void gradleRunsTheTestTaskItIsNamed() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("suites"), work.resolve("suites"));

    var unit = Samples.mutastic("run", project.toString(), "--format=agent");
    var integration =
        Samples.mutastic("run", project.toString(), "--format=agent", "--suite=integrationTest");

    assertThat(unit.out()).contains("1 missed").contains("no test checks `late()` at `days == 30`");
    assertThat(integration.out()).contains("0 missed, 0 not covered, 5 caught of 5 mutants");
    // Its run keeps its own record, beside the default suite's.
    assertThat(project.resolve("build/mutastic/suites/integrationTest/last-run.tsv")).exists();
    assertThat(project.resolve("build/mutastic/last-run.tsv")).exists();
  }

  @Test
  void aSuiteTheBuildDoesntHaveSaysWhichItHas() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("suites"), work.resolve("suites"));

    var run = Samples.mutastic("run", project.toString(), "--suite=integration");

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .contains(
            "no test suite integration in the root project; its Test tasks are integrationTest,"
                + " test");
  }

  @Test
  void mavenRunsFailsafesTestsAsIntegrationTest() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("maven-suites"), work.resolve("maven-suites"));

    var unit = Samples.mutastic("run", project.toString(), "--format=agent");
    var integration =
        Samples.mutastic("run", project.toString(), "--format=agent", "--suite=integration-test");

    assertThat(unit.out()).contains("1 missed");
    assertThat(integration.out()).contains("0 missed, 0 not covered, 5 caught of 5 mutants");
    assertThat(Files.exists(project.resolve("target/mutastic/suites/integration-test"))).isTrue();
  }
}
