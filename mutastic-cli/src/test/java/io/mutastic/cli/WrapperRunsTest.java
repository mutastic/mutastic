package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/// A build runs with the Gradle it was made for, its wrapper, wherever the wrapper is: a Gradle on
/// the PATH may be too old to read it. Each wrapper here notes that it ran, then runs the real one.
@DisabledOnOs(value = OS.WINDOWS, disabledReason = "the Unix wrapper; Windows runs gradlew.bat")
class WrapperRunsTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");

  @TempDir Path work;

  /// A module with settings of its own, the repository's wrapper at its top: the wrapper builds the
  /// module's own build, as it finds its files beside itself.
  @Test
  void aNestedBuildRunsTheRepositorysWrapper() throws Exception {
    var repository = Files.createDirectories(work.resolve("repository"));
    Files.createDirectories(repository.resolve(".git"));
    Files.writeString(repository.resolve("settings.gradle.kts"), "rootProject.name = \"outer\"\n");
    var module = Samples.copy(SAMPLE, repository.resolve("services/pricing"));
    Files.move(module.resolve("gradle"), repository.resolve("gradle"));
    Files.move(module.resolve("gradlew"), repository.resolve("gradlew-real"));
    Files.delete(module.resolve("gradlew.bat"));
    var ran = noting(repository.resolve("gradlew"));
    repository.resolve("gradlew").toFile().setExecutable(true);

    var mutants = Samples.mutastic("mutants", module.toString());

    assertThat(mutants.exit()).as(mutants.err()).isZero();
    assertThat(ran).exists();
  }

  /// A checkout can lose a wrapper's executable bit: it runs with `sh`, as `sh gradlew` would.
  @Test
  void aWrapperThatIsntExecutableRunsWithSh() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    Files.move(project.resolve("gradlew"), project.resolve("gradlew-real"));
    var ran = noting(project.resolve("gradlew"));
    project.resolve("gradlew").toFile().setExecutable(false);
    project.resolve("gradlew-real").toFile().setExecutable(false);

    var mutants = Samples.mutastic("mutants", project.toString());

    assertThat(mutants.exit()).as(mutants.err()).isZero();
    assertThat(ran).exists();
  }

  /// A wrapper at `wrapper` that writes the file this returns, then runs `gradlew-real` beside it.
  private static Path noting(Path wrapper) throws Exception {
    var ran = wrapper.resolveSibling("ran");
    Files.writeString(
        wrapper,
        """
        #!/bin/sh
        here=$(dirname "$0")
        touch "$here/ran"
        exec sh "$here/gradlew-real" "$@"
        """);
    return ran;
  }
}
