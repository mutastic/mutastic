package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Tests the build runs that the JUnit Platform can't start: the worker's discovery exception is
/// the headline, then what to change, as `no-tests`. Both once read as a test that called
/// `System.exit` (`tests-stopped`), which sent people looking for one.
class TestsCantStartTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));

  @TempDir Path work;

  /// `samples/testng-old`: TestNG 6.9.10, older than TestNG's engine runs.
  @Test
  void anOldTestNgIsNamed() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("testng-old"), work.resolve("testng-old"));

    var run = Samples.mutastic("run", project.toString(), "--report=none");

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .contains("the tests in testng-old can't start on the JUnit Platform: TestEngine with ID")
        .contains("NoSuchMethodError")
        .contains("this build has TestNG 6.9.10. Upgrade org.testng:testng to 6.14.3 or later.")
        .doesNotContain("System.exit");
  }

  /// `samples/junit3`: JUnit 3.8.2 alone, which Surefire runs and no engine on the platform does.
  @Test
  void junit3AloneIsNamed() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("junit3"), work.resolve("junit3"));

    var run =
        Samples.mutastic(
            java.util.Map.of("MUTASTIC_FORMAT", "jsonl"),
            "run",
            project.toString(),
            "--report=none");

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.USAGE);
    assertThat(run.out())
        .contains("\"code\":\"no-tests\"")
        .contains("Cannot create Launcher without at least one TestEngine")
        .contains("Change junit:junit to 4.13.2")
        .doesNotContain("System.exit");
  }
}
