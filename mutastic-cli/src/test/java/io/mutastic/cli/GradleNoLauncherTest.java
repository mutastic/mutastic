package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `samples/gradle-no-launcher`: Gradle 8.6 and JUnit 5 with no `junit-platform-launcher` declared,
/// as Gradle 8 runs the platform with a launcher of its own. The worker has none of its own either,
/// so it didn't start: "internal error", exit 4. mutastic adds the build's launcher, at its
/// engine's version (corpus survey A7a).
class GradleNoLauncherTest {
  private static final Path SAMPLE =
      Path.of(System.getProperty("mutastic.samples"), "gradle-no-launcher");

  @TempDir Path work;

  @Test
  void theBuildsOwnLauncherIsAdded() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("gradle-no-launcher"));
    // Gradle 8.6 runs on JDK 21 at most: its daemon gets the JDK 17 the build has.
    var jdk17 = System.getProperty("mutastic.jdk17").replace('\\', '/');
    Files.writeString(project.resolve("gradle.properties"), "org.gradle.java.home=" + jdk17 + "\n");

    var run = Samples.mutastic("run", project.toString(), "--format=brief", "--report=none");

    assertThat(run.err()).doesNotContain("internal error");
    assertThat(run.out()).as(run.err()).contains("Score  50.0%");
    assertThat(Files.readString(project.resolve("build/mutastic/setup.properties")))
        .contains("junit-platform-launcher-1.10.2.jar")
        .doesNotContain("junit-platform-launcher-6.");
  }
}
