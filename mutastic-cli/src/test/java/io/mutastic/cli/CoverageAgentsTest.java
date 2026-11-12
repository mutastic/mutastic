package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A coverage tool the build applies is left out of mutastic's run, and the run is the same as
/// without it. Once, Kover's agent stayed in: its jar, which Kover writes in a task of its own
/// before the tests, wasn't there, and the test worker didn't start.
class CoverageAgentsTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "kotlin");

  @TempDir Path work;

  @Test
  void koverChangesNothing() throws Exception {
    var plain = Samples.copy(SAMPLE, work.resolve("plain"));
    var kover = Samples.copy(SAMPLE, work.resolve("kover"));
    var build = kover.resolve("build.gradle.kts");
    var text = Files.readString(build);
    var kotlin = "    kotlin(\"jvm\") version \"2.2.21\"\n";
    var applied =
        text.replace(
            kotlin, kotlin + "    id(\"org.jetbrains.kotlinx.kover\") version \"0.9.1\"\n");
    assertThat(applied).as("the sample's plugins block").isNotEqualTo(text);
    Files.writeString(build, applied);

    var without = Samples.mutastic("run", plain.toString(), "--format=concise", "--report=none");
    var with = Samples.mutastic("run", kover.toString(), "--format=concise", "--report=none");

    assertThat(with.err()).doesNotContain("mutastic:");
    assertThat(mutants(with.out())).isEqualTo(mutants(without.out())).contains("caught");
  }

  /// The summary's `Mutants` line: what was caught, missed and skipped.
  private static String mutants(String out) {
    return out.lines().filter(line -> line.strip().startsWith("Mutants")).findFirst().orElse(out);
  }
}
