package io.mutastic.gradle;

import static org.assertj.core.api.Assertions.assertThat;

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
}
