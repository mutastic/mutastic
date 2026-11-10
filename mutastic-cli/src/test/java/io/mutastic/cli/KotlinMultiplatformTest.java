package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A Kotlin Multiplatform module with a `jvm("desktop")` target (`samples/kotlin-multiplatform`):
/// no `java` plugin, its code in the target's compilations, its tests in `desktopTest`, on JUnit 4
/// with no JUnit Platform of their own, and its files named for where they run. One run, shared.
class KotlinMultiplatformTest {
  private static final Path SAMPLE =
      Path.of(System.getProperty("mutastic.samples"), "kotlin-multiplatform");

  @TempDir static Path work;

  private static Samples.Result run;
  private static List<String> mutants;

  @BeforeAll
  static void runTheSample() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("kotlin-multiplatform"));
    run = Samples.mutastic("run", project.toString(), "--format=jsonl", "--report=none");
    mutants =
        run.out().lines().filter(line -> line.contains("\"reason\":\"mutant-finished\"")).toList();
  }

  /// The target's tests run, through mutastic's own platform and JUnit 4's engine on it.
  @Test
  void theTargetsTestsRunOnItsCode() {
    assertThat(run.out()).contains("\"reason\":\"run-finished\"");
    assertThat(mutants)
        .anyMatch(
            mutant ->
                mutant.contains("\"status\":\"Killed\"")
                    && mutant.contains("com.example.tally.TallyTest#aHighScoreGetsTheBonus"));
  }

  /// `Tally.common.kt` is kotlinc's `Tally_commonKt`: the file is the one its class file names.
  @Test
  void aFileWithADotInItsNameIsTheSourcesOwn() {
    assertThat(mutants)
        .anyMatch(
            mutant ->
                mutant.contains(
                    "\"file\":\"src/commonMain/kotlin/com/example/tally/Tally.common.kt\""))
        .anyMatch(
            mutant ->
                mutant.contains(
                    "\"file\":\"src/desktopMain/kotlin/com/example/tally/Tally.desktop.kt\""))
        .noneMatch(
            mutant -> mutant.contains("_commonKt.java") || mutant.contains("_desktopKt.java"));
  }
}
