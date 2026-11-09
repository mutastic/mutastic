package io.mutastic.gradle.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The Gradle command the plugin's printed commands start with: the build's wrapper as typed from
/// where the person ran it, or `gradle` for a build without one.
class GradleCommandTest {
  @Test
  void aBuildWithAWrapperRunsIt(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("gradlew"), "#!/bin/sh\n");
    var module = Files.createDirectories(root.resolve("core")).toFile();

    assertThat(MutasticPlugin.gradle(root.toFile(), root.toFile())).isEqualTo("./gradlew");
    assertThat(MutasticPlugin.gradle(root.toFile(), module))
        .isEqualTo(root.resolve("gradlew").toString());
  }

  @Test
  void aBuildWithoutOneRunsGradle(@TempDir Path root) {
    assertThat(MutasticPlugin.gradle(root.toFile(), new File(root.toFile(), "core")))
        .isEqualTo("gradle");
  }
}
