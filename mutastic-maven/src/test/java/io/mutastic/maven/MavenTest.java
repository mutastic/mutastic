package io.mutastic.maven;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.api.BuildTool.SetupFailed;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
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

  /// The build runs as it does for the person: with its wrapper when it has one.
  @Test
  void aBuildWithAWrapperRunsWithIt(@TempDir Path directory) throws Exception {
    Files.createDirectories(directory.resolve(".mvn/wrapper"));
    var wrapper = executable(directory.resolve("mvnw"));

    assertThat(MavenSetup.mvn(directory, onPath(directory)).command())
        .containsExactly(wrapper.toString());
  }

  @Test
  void aBuildWithoutAWrapperRunsWithTheMvnOnThePath(@TempDir Path directory) throws Exception {
    assertThat(MavenSetup.mvn(directory, onPath(directory)).command()).containsExactly("mvn");
  }

  @Test
  void withNeitherTheSetupSaysSo(@TempDir Path directory) {
    assertThatThrownBy(() -> MavenSetup.mvn(directory, ""))
        .isInstanceOf(SetupFailed.class)
        .hasMessage(
            directory + " has no Maven wrapper (mvnw), and there's no `mvn` to run instead");
  }

  /// A module's directory runs the wrapper at the top of its build, where `.mvn` is; one above that
  /// belongs to something else.
  @Test
  void aModuleDirectoryRunsItsBuildsWrapper(@TempDir Path directory) throws Exception {
    executable(directory.resolve("mvnw"));
    var root = Files.createDirectories(directory.resolve("app"));
    Files.createDirectories(root.resolve(".mvn/wrapper"));
    var wrapper = executable(root.resolve("mvnw"));
    var module = Files.createDirectories(root.resolve("services/api"));

    assertThat(MavenSetup.mvn(module, onPath(directory)).command())
        .containsExactly(wrapper.toString());

    Files.delete(wrapper);

    assertThat(MavenSetup.mvn(module, onPath(directory)).command()).containsExactly("mvn");
  }

  /// A module with a `.mvn` of its own (for its `jvm.config`, say) still runs the repository's
  /// wrapper, and not an `mvn` on the PATH.
  @Test
  void aModuleWithItsOwnMvnRunsTheRepositorysWrapper(@TempDir Path directory) throws Exception {
    Files.createDirectories(directory.resolve(".git"));
    Files.createDirectories(directory.resolve(".mvn/wrapper"));
    var wrapper = executable(directory.resolve("mvnw"));
    var module = Files.createDirectories(directory.resolve("services/api/.mvn")).getParent();

    assertThat(MavenSetup.mvn(module, onPath(directory)).command())
        .containsExactly(wrapper.toString());
  }

  /// A checkout can lose a wrapper's executable bit: it runs with `sh`.
  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows runs mvnw.cmd as it is")
  void aWrapperThatIsntExecutableRunsWithSh(@TempDir Path directory) throws Exception {
    Files.createDirectories(directory.resolve(".mvn/wrapper"));
    var wrapper = Files.writeString(directory.resolve("mvnw"), "#!/bin/sh\n");

    assertThat(MavenSetup.mvn(directory, onPath(directory)).command())
        .containsExactly("sh", wrapper.toString());
  }

  /// With no wrapper, mutastic says which Maven runs, and its version when its jars say.
  @Test
  void theMvnOnThePathIsSaidWithItsVersion(@TempDir Path directory) throws Exception {
    var home = Files.createDirectories(directory.resolve("apache-maven-3.9.11"));
    var mvn = executable(Files.createDirectories(home.resolve("bin")).resolve("mvn"));
    Files.writeString(
        Files.createDirectories(home.resolve("lib")).resolve("maven-core-3.9.11.jar"), "");

    var tool = MavenSetup.mvn(directory, home.resolve("bin").toString());

    assertThat(MavenSetup.onPath(tool))
        .isEqualTo(
            "no Maven wrapper (mvnw) here or above: running Maven 3.9.11 from the PATH, " + mvn);
  }

  /// A PATH with an `mvn` on it, in `directory/bin`.
  private static String onPath(Path directory) throws Exception {
    executable(Files.createDirectories(directory.resolve("bin")).resolve("mvn"));
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
