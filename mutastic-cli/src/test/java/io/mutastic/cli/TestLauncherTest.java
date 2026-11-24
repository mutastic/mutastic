package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The JDK a Gradle test task runs its tests on, when the task has no launcher: as Gradle 7 leaves
/// it without a toolchain. Its setup read the launcher alone, and stopped there.
class TestLauncherTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));

  @TempDir Path work;

  @Test
  void aTestTaskWithNoLauncherRunsOnTheJdkGradleGivesIt() throws Exception {
    var project = Samples.copy(SAMPLES.resolve("pricing"), work.resolve("pricing"));
    var build = project.resolve("build.gradle.kts");
    Files.writeString(
        build,
        Files.readString(build)
            + """

            // As Gradle 7 leaves the task without a toolchain: no launcher.
            tasks.test {
                javaLauncher.convention(null as JavaLauncher?)
                javaLauncher.set(null as JavaLauncher?)
            }
            """);

    var run = Samples.mutastic("run", project.toString(), "--format=concise");

    assertThat(run.exit()).as(run.out() + run.err()).isZero();
    assertThat(run.out()).contains("Tests  3 on JDK").doesNotContain("javaLauncher");
  }
}
