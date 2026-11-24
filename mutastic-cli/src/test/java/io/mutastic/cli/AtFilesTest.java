package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// In a build of several modules, `--at`'s file picks the module the run is of, as a directory
/// inside a build walks up to it: `run --at=app/src/main/java/…/Checkout.java:42` from the top runs
/// `:app`. Found from the files alone, before the build is read.
class AtFilesTest {
  @TempDir Path build;

  @Test
  void aGradleModulesFileRunsThatModule() throws IOException {
    gradleBuild();
    var file = source("app/src/main/java/com/example/app/Checkout.java");

    assertThat(moduleOf(file)).isEqualTo(":app");
  }

  @Test
  void aNestedGradleModuleIsNamedByEachDirectory() throws IOException {
    gradleBuild();
    var file = source("libs/core/src/main/java/com/example/core/Fees.java");

    assertThat(moduleOf(file)).isEqualTo(":libs:core");
  }

  /// A module of its own build file is found by it, whatever its sources are called.
  @Test
  void aModuleWithItsOwnBuildFileIsThatModule() throws IOException {
    gradleBuild();
    Files.createDirectories(build.resolve("web"));
    Files.writeString(build.resolve("web/build.gradle.kts"), "");
    var file = source("web/java/com/example/web/Pages.java");

    assertThat(moduleOf(file)).isEqualTo(":web");
  }

  /// A package called `src` is the module's sources, not a module.
  @Test
  void aPackageCalledSrcIsntAModule() throws IOException {
    gradleBuild();
    var file = source("app/src/main/java/com/src/Foo.java");

    assertThat(moduleOf(file)).isEqualTo(":app");
  }

  @Test
  void aFileOfTheBuildsOwnSourcesKeepsItsOwnModule() throws IOException {
    gradleBuild();
    var file = source("src/main/java/com/example/Pricing.java");

    assertThat(moduleOf(file)).isEqualTo(":");
  }

  /// Maven names a module by its directory, as `-pl` does.
  @Test
  void aMavenModuleIsNamedByItsDirectory() throws IOException {
    Files.writeString(build.resolve("pom.xml"), "<project/>");
    Files.createDirectories(build.resolve("libs/core"));
    Files.writeString(build.resolve("libs/pom.xml"), "<project/>");
    Files.writeString(build.resolve("libs/core/pom.xml"), "<project/>");
    var file = source("libs/core/src/main/java/com/example/core/Fees.java");

    assertThat(moduleOf(file)).isEqualTo(":libs/core");
  }

  /// The path is from the build's directory here, as a card shows it; one from where mutastic runs,
  /// or absolute, is the same file.
  @Test
  void aPathFromTheBuildsDirectoryIsFound() throws IOException {
    gradleBuild();
    source("app/src/main/java/com/example/app/Checkout.java");
    var command =
        Main.Command.parse(
            new String[] {
              "mutants", build.toString(), "--at=app/src/main/java/com/example/app/Checkout.java:9"
            });

    assertThat(AtFiles.onItsModule(command).module()).isEqualTo(":app");
  }

  @Test
  void aModuleNamedIsKept() throws IOException {
    gradleBuild();
    var file = source("app/src/main/java/com/example/app/Checkout.java");
    var command =
        Main.Command.parse(new String[] {"run", build.toString(), ":core", "--at=" + file + ":9"});

    assertThat(AtFiles.onItsModule(command).module()).isEqualTo(":core");
  }

  @Test
  void aFileOutsideTheBuildKeepsTheCommand() throws IOException {
    gradleBuild();
    var elsewhere = Files.createTempFile("Elsewhere", ".java");
    try {
      assertThat(moduleOf(elsewhere)).isEqualTo(":");
    } finally {
      Files.delete(elsewhere);
    }
  }

  /// A Windows path's `\` separates its parts on every platform.
  @Test
  void eitherSeparatorSplitsThePath() throws IOException {
    gradleBuild();
    source("app/src/main/java/com/example/app/Checkout.java");
    var command =
        Main.Command.parse(
            new String[] {
              "run",
              build.toString(),
              "--at=app\\src\\main\\java\\com\\example\\app\\Checkout.java:9"
            });

    assertThat(AtFiles.onItsModule(command).module()).isEqualTo(":app");
  }

  private String moduleOf(Path file) {
    var command = Main.Command.parse(new String[] {"run", build.toString(), "--at=" + file + ":9"});
    return AtFiles.onItsModule(command).module();
  }

  private void gradleBuild() throws IOException {
    Files.writeString(build.resolve("settings.gradle.kts"), "include(\"app\")\n");
  }

  private Path source(String path) throws IOException {
    var file = build.resolve(path);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "class X {}\n");
    return file;
  }
}
