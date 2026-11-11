package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// What a copy of a sample holds, which is everything the sample has and nothing a build of it
/// left. A machine that has run the samples has build output in them, and a copy that carried a
/// module's across made the tests reading that module's output read a previous run's: the sample
/// here has output at its top and in a module, as a built multi-module sample does, so a copy that
/// keeps any of it fails on every machine rather than only on one where the samples have been
/// built.
class SamplesTest {
  @TempDir Path work;

  @Test
  void aCopyKeepsTheSampleAndNoneOfWhatABuildOfItLeft() throws Exception {
    var sample = work.resolve("sample");
    write(sample.resolve("settings.gradle.kts"), "rootProject.name = \"sample\"\n");
    write(sample.resolve("app/build.gradle.kts"), "plugins {\n    java\n}\n");
    write(sample.resolve("app/src/main/java/com/example/Fees.java"), "class Fees {}\n");
    write(sample.resolve(".gradle/file-system.probe"), "a previous build's\n");
    write(sample.resolve("build/reports/tests/index.html"), "a previous build's\n");
    write(sample.resolve("app/build/test-results/TEST-FeesTest.xml"), "a previous build's\n");
    write(sample.resolve("app/target/classes/Fees.class"), "a previous build's\n");

    var copy = Samples.copy(sample, work.resolve("copy"));

    assertThat(copy.resolve("settings.gradle.kts")).exists();
    assertThat(copy.resolve("app/build.gradle.kts")).exists();
    assertThat(copy.resolve("app/src/main/java/com/example/Fees.java")).exists();
    assertThat(copy.resolve(".gradle")).doesNotExist();
    assertThat(copy.resolve("build")).doesNotExist();
    assertThat(copy.resolve("app/build")).doesNotExist();
    assertThat(copy.resolve("app/target")).doesNotExist();
  }

  private static void write(Path file, String content) throws Exception {
    Files.createDirectories(file.getParent());
    Files.writeString(file, content);
  }
}
