package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A Compose for Desktop app (`samples/compose`): what the Compose compiler writes into each
/// `@Composable` (the `Composer`, its groups, the `$changed` and `$default` masks, `remember`'s
/// cache, trace and source information) is skipped as generated, and what the person wrote in the
/// same functions keeps its mutants. One run, shared.
class ComposeTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "compose");

  /// What a mutant's change shows of the Compose runtime, when it's one of its calls.
  private static final List<String> RUNTIME =
      List.of(
          "androidx/compose/runtime/Composer.",
          "androidx/compose/runtime/ComposerKt.",
          "RecomposeScopeImplKt.updateChangedFlags",
          "ScopeUpdateScope.updateScope");

  @TempDir static Path work;

  private static List<String> mutants;

  @BeforeAll
  static void runTheSample() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("compose"));
    mutants =
        Samples.mutastic("run", project.toString(), "--format=jsonl", "--report=none")
            .out()
            .lines()
            .filter(line -> line.contains("\"reason\":\"mutant-finished\""))
            .toList();
  }

  @Test
  void theComposeRuntimesCallsAreSkippedAsGenerated() {
    var ran =
        mutants.stream().filter(mutant -> !mutant.contains("\"status\":\"Ignored\"")).toList();

    for (var call : RUNTIME) {
      assertThat(ran).noneMatch(mutant -> mutant.contains(call));
    }
    assertThat(mutants).anyMatch(mutant -> mutant.contains("the Compose compiler's bookkeeping"));
  }

  /// `when` in `Summary`, a composable, is the person's: its mutants run.
  @Test
  void whatThePersonWroteInAComposableKeepsItsMutants() {
    assertThat(mutants)
        .anyMatch(
            mutant ->
                mutant.contains("\"method\":\"Summary(")
                    && !mutant.contains("\"status\":\"Ignored\""));
  }

  /// A card names what the source names: `Text`, not kotlinc's `Text--4IGK_g`; a lambda the
  /// compiler keeps in `ComposableSingletons$FocusAppKt` is in `FocusApp.kt`, a composable lambda;
  /// and a composable's `$changed` mask is no input the tests passed.
  @Test
  void aCardSaysWhatTheSourceSays() {
    var singletons =
        mutants.stream()
            .filter(mutant -> mutant.contains("ComposableSingletons"))
            .filter(mutant -> !mutant.contains("\"status\":\"Ignored\""))
            .toList();

    assertThat(singletons)
        .isNotEmpty()
        .allMatch(mutant -> mutant.contains("\"file\":\"src/main/kotlin/com/example/compose/"));
    assertThat(mutants)
        .anyMatch(mutant -> mutant.contains("the call to `Text()` is removed"))
        .noneMatch(mutant -> mutant.contains("4IGK_g()`"))
        .noneMatch(mutant -> mutant.contains("inputs seen: $changed"))
        .anyMatch(mutant -> mutant.contains("in a composable lambda"));
  }
}
