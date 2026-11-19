package io.mutastic.gradle.plugin;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/// The Gradle command the plugin's printed commands start with: the build's wrapper as typed from
/// where the person ran it, or `gradle` for a build without one.
class GradleCommandTest {
  @Test
  void aBuildWithAWrapperRunsIt(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("gradlew"), "#!/bin/sh\n");
    root.resolve("gradlew").toFile().setExecutable(true);
    var module = Files.createDirectories(root.resolve("core")).toFile();

    assertThat(MutasticPlugin.gradle(root.toFile(), root.toFile())).isEqualTo("./gradlew");
    assertThat(MutasticPlugin.gradle(root.toFile(), module))
        .isEqualTo(root.resolve("gradlew").toString());
  }

  /// cmd reads `./gradlew` as a switch, so a person on Windows is shown the batch file, in the form
  /// cmd and PowerShell both run.
  @Test
  void onWindowsTheWrapperIsItsBatchFile(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("gradlew"), "#!/bin/sh\n");
    root.resolve("gradlew").toFile().setExecutable(true);
    Files.writeString(root.resolve("gradlew.bat"), "@rem\n");

    assertThat(MutasticPlugin.gradle(root.toFile(), root.toFile(), true))
        .isEqualTo(".\\gradlew.bat");
    assertThat(MutasticPlugin.gradle(root.toFile(), root.toFile(), false)).isEqualTo("./gradlew");
  }

  @Test
  void aBuildWithoutOneRunsGradle(@TempDir Path root) {
    assertThat(MutasticPlugin.gradle(root.toFile(), new File(root.toFile(), "core")))
        .isEqualTo("gradle");
  }

  /// A build nested in another, with settings of its own, shares the wrapper at the top of its
  /// repository, as mutastic's command line finds it.
  @Test
  void aNestedBuildRunsTheRepositorysWrapper(@TempDir Path repository) throws Exception {
    Files.createDirectories(repository.resolve(".git"));
    Files.writeString(repository.resolve("gradlew"), "#!/bin/sh\n");
    repository.resolve("gradlew").toFile().setExecutable(true);
    var nested = Files.createDirectories(repository.resolve("gateway")).toFile();

    assertThat(MutasticPlugin.gradle(nested, repository.toFile(), false)).isEqualTo("./gradlew");
    assertThat(MutasticPlugin.gradle(nested, nested, false))
        .isEqualTo(repository.resolve("gradlew").toString());
  }

  /// A wrapper that isn't executable runs with `sh`, so the command runs as it's copied.
  @Test
  @DisabledOnOs(value = OS.WINDOWS, disabledReason = "Windows runs gradlew.bat as it is")
  void aWrapperThatIsntExecutableRunsWithSh(@TempDir Path root) throws Exception {
    Files.writeString(root.resolve("gradlew"), "#!/bin/sh\n");
    root.resolve("gradlew").toFile().setExecutable(false);

    assertThat(MutasticPlugin.gradle(root.toFile(), root.toFile(), false))
        .isEqualTo("sh ./gradlew");
  }
}
