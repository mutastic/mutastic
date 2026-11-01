package io.mutastic.maven;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MavenTest {
  private final Maven maven = new Maven();

  /// In the module's own `target`, which `mvn clean` clears; a build's checks (Apache RAT) look at
  /// what's left outside it.
  @Test
  void eachModuleKeepsItsWorkInItsOwnTarget() {
    var project = Path.of("/code/app");

    assertThat(maven.workDirectory(project, ":")).isEqualTo(Path.of("/code/app/target/mutastic"));
    assertThat(maven.workDirectory(project, ":services/api"))
        .isEqualTo(Path.of("/code/app/services/api/target/mutastic"));
  }

  @Test
  void aBuildIsADirectoryWithAPom(@TempDir Path directory) throws Exception {
    assertThat(maven.recognises(directory)).isFalse();

    Files.writeString(directory.resolve("pom.xml"), "<project/>");

    assertThat(maven.recognises(directory)).isTrue();
  }
}
