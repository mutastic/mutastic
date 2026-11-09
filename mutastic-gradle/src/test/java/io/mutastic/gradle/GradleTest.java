package io.mutastic.gradle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.api.BuildTool.SetupFailed;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class GradleTest {
  private final Gradle gradle = new Gradle();

  @Test
  void eachModuleKeepsItsOwnWorkDirectory() {
    var project = Path.of("/code/app");

    assertThat(gradle.workDirectory(project, ":")).isEqualTo(Path.of("/code/app/build/mutastic"));
    assertThat(gradle.workDirectory(project, ":data"))
        .isEqualTo(Path.of("/code/app/build/mutastic/data"));
    assertThat(gradle.workDirectory(project, ":services:api"))
        .isEqualTo(Path.of("/code/app/build/mutastic/services/api"));
  }

  @Test
  void aBuildIsADirectoryWithGradlesBuildFiles(@TempDir Path directory) throws Exception {
    assertThat(gradle.recognises(directory)).isFalse();

    Files.writeString(directory.resolve("settings.gradle.kts"), "");

    assertThat(gradle.recognises(directory)).isTrue();
  }

  /// The build runs as it does for the person: with its wrapper when it has one.
  @Test
  void aBuildWithAWrapperRunsWithIt(@TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve("settings.gradle.kts"), "");
    var wrapper = executable(directory.resolve("gradlew"));

    assertThat(GradleSetup.gradle(directory, onPath(directory))).isEqualTo(wrapper.toString());
  }

  @Test
  void aBuildWithoutAWrapperRunsWithTheGradleOnThePath(@TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve("settings.gradle.kts"), "");

    assertThat(GradleSetup.gradle(directory, onPath(directory))).isEqualTo("gradle");
  }

  @Test
  void withNeitherTheSetupSaysSo(@TempDir Path directory) throws Exception {
    Files.writeString(directory.resolve("settings.gradle.kts"), "");

    assertThatThrownBy(() -> GradleSetup.gradle(directory, ""))
        .isInstanceOf(SetupFailed.class)
        .hasMessage(
            directory + " has no Gradle wrapper (gradlew), and there's no `gradle` to run instead");
  }

  /// A module's directory runs the wrapper at the top of its build, where its settings are; one
  /// above that belongs to something else.
  @Test
  void aModuleDirectoryRunsItsBuildsWrapper(@TempDir Path directory) throws Exception {
    executable(directory.resolve("gradlew"));
    var root = Files.createDirectories(directory.resolve("app"));
    Files.writeString(root.resolve("settings.gradle.kts"), "");
    var wrapper = executable(root.resolve("gradlew"));
    var module = Files.createDirectories(root.resolve("libs/core"));
    Files.writeString(module.resolve("build.gradle.kts"), "");

    assertThat(GradleSetup.gradle(module, onPath(directory))).isEqualTo(wrapper.toString());

    Files.delete(wrapper);

    assertThat(GradleSetup.gradle(module, onPath(directory))).isEqualTo("gradle");
  }

  /// Run from a module's directory, Gradle runs the whole build: a task is named from its top.
  @Test
  void aModuleDirectorysTasksAreNamedFromTheTopOfItsBuild(@TempDir Path directory)
      throws Exception {
    Files.writeString(directory.resolve("settings.gradle.kts"), "");
    var module = Files.createDirectories(directory.resolve("libs/core"));
    Files.writeString(module.resolve("build.gradle.kts"), "");

    assertThat(GradleSetup.task(directory, ":", "mutasticSetup")).isEqualTo(":mutasticSetup");
    assertThat(GradleSetup.task(directory, ":data", "mutasticSetup"))
        .isEqualTo(":data:mutasticSetup");
    assertThat(GradleSetup.task(module, ":", "mutasticSetup"))
        .isEqualTo(":libs:core:mutasticSetup");
  }

  /// A PATH with a `gradle` on it, in `directory/bin`.
  private static String onPath(Path directory) throws Exception {
    executable(Files.createDirectories(directory.resolve("bin")).resolve("gradle"));
    return directory.resolve("bin").toString();
  }

  private static Path executable(Path file) throws Exception {
    if (!Files.exists(file)) {
      Files.writeString(file, "#!/bin/sh\n");
    }
    assertThat(file.toFile().setExecutable(true)).isTrue();
    return file;
  }
}
