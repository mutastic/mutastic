package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A build whose JDK toolchain asks for a JDK the build tool finds none of:
/// `samples/toolchain-missing` (Gradle) and `samples/maven-toolchain-missing` (the toolchains
/// plugin), each for a JDK 99. It came out as Gradle's or Maven's failure quoted whole; now it
/// names the JDK, from the build's own words, and the run again with the argument that tells the
/// build tool where one is.
class ToolchainMissingTest {
  private static final Path SAMPLES = Path.of(System.getProperty("mutastic.samples"));

  @TempDir Path work;

  /// Its command keeps the arguments typed after `--`, once, before the one it adds.
  @Test
  void gradleIsToldWhereTheJdkIs() throws Exception {
    var project =
        Samples.copy(SAMPLES.resolve("toolchain-missing"), work.resolve("toolchain-missing"));

    var run =
        Samples.mutastic(
            java.util.Map.of("MUTASTIC_FORMAT", "jsonl"), "run", project.toString(), "--", "-Pci");

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .contains(
            "mutastic: the build needs JDK 99 for its toolchain, and Gradle found"
                + " none.\nInstall one, or tell Gradle where it is:\n  mutastic run "
                + project
                + " -- -Pci -Dorg.gradle.java.installations.paths=<path/to/jdk-99>\n"
                + "For every run, add it to build: args: in mutastic.yaml, or to"
                + " MUTASTIC_BUILD_ARGS. Gradle's output is in ")
        .doesNotContain("doesn't build", "-- -Pci -- -Pci", "* Try:");
    assertThat(run.out()).contains("\"code\":\"build-jdk\"", "\"applicability\":\"placeholders\"");
  }

  @Test
  void mavenIsPointedAtItsToolchainsFile() throws Exception {
    var project =
        Samples.copy(
            SAMPLES.resolve("maven-toolchain-missing"), work.resolve("maven-toolchain-missing"));

    var run =
        Samples.mutastic(java.util.Map.of("MUTASTIC_FORMAT", "jsonl"), "run", project.toString());

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .contains(
            "mutastic: the build needs JDK 99 (temurin) for its toolchain, and Maven found none"
                + " in ~/.m2/toolchains.xml.\nAdd it to ~/.m2/toolchains.xml, or tell Maven which"
                + " toolchains file has it:\n  mutastic run "
                + project
                + " -- --toolchains <path/to/toolchains.xml>\n"
                + "For every run, add it to build: args: in mutastic.yaml, or to"
                + " MUTASTIC_BUILD_ARGS. Maven's output is in ");
    assertThat(run.out()).contains("\"code\":\"build-jdk\"");
  }

  /// The command it offers, filled in with a toolchains file that has the JDK, gets past it.
  @Test
  void mavensCommandFilledInGetsPastIt() throws Exception {
    var project =
        Samples.copy(
            SAMPLES.resolve("maven-toolchain-missing"), work.resolve("maven-toolchain-missing"));
    var toolchains = work.resolve("toolchains.xml");
    Files.writeString(
        toolchains,
        """
        <toolchains>
          <toolchain>
            <type>jdk</type>
            <provides><version>99</version><vendor>temurin</vendor></provides>
            <configuration><jdkHome>%s</jdkHome></configuration>
          </toolchain>
        </toolchains>
        """
            .formatted(System.getProperty("java.home")));

    var run =
        Samples.mutastic(
            "run",
            project.toString(),
            "--format=agent",
            "--report=none",
            "--",
            "--toolchains",
            toolchains.toString());

    // Its one test leaves a mutant missed, so a run that gets to the mutants ends in 0 or 1.
    assertThat(run.exit()).as(run.err()).isIn(0, 1);
    assertThat(run.err()).doesNotContain("toolchain");
  }
}
