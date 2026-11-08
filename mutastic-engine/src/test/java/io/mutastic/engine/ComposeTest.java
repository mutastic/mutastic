package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// What the Compose compiler writes into a `@Composable`, told from what the person wrote there, on
/// real output: `FocusAppKt.class` and its lambdas' singleton, as Kotlin 2.2.21 and Compose
/// Multiplatform 1.9.3 compile `samples/compose`'s `FocusApp.kt`. Kept as class files, so nothing
/// is compiled here; when the sample's `FocusApp.kt` changes, copy them again from its
/// `build/classes/kotlin/main`.
class ComposeTest {
  private static final Path KEPT = Path.of("src/test/resources/kotlin/compose");

  private static List<Mutant> mutants;

  @BeforeAll
  static void findTheMutants() throws IOException {
    var context = ClassFile.of();
    var models =
        List.of(
            context.parse(Files.readAllBytes(KEPT.resolve("FocusAppKt.class"))),
            context.parse(
                Files.readAllBytes(KEPT.resolve("ComposableSingletons$FocusAppKt.class"))));
    var mutations =
        new Mutations(
            context, new Policy(List.of(), List.of(), List.of(), List.of("all"), List.of()));
    mutations.prepare(models);
    mutants = models.stream().flatMap(model -> mutations.find(model).stream()).toList();
  }

  private static List<Mutant> running() {
    return mutants.stream().filter(mutant -> mutant.skipped().isEmpty()).toList();
  }

  private static List<Mutant> in(String method) {
    return mutants.stream().filter(mutant -> mutant.methodName().equals(method)).toList();
  }

  /// Every call to the runtime's bookkeeping: groups, `remember`'s cache, `changed`, skipping,
  /// trace and source information, the restart scope.
  @Test
  void noCallToTheComposeRuntimeRuns() {
    assertThat(running())
        .noneMatch(mutant -> mutant.original().contains(" androidx/compose/runtime/Composer."))
        .noneMatch(mutant -> mutant.original().contains(" androidx/compose/runtime/ComposerKt."))
        .noneMatch(mutant -> mutant.original().contains("ScopeUpdateScope."))
        .noneMatch(mutant -> mutant.original().contains("ComposableLambdaKt."));
  }

  /// `Summary`'s `when` is the person's; its `$changed` mask and its skipping aren't. In the source
  /// the function starts at line 107 and its `when` is on lines 110 to 113.
  @Test
  void aComposablesOwnBranchesRunAndItsMasksDont() {
    var branches =
        in("Summary").stream()
            .filter(mutant -> mutant.operator().id().equals("mutastic/remove-conditional"))
            .toList();

    assertThat(branches)
        .filteredOn(mutant -> mutant.line() >= 110 && mutant.line() <= 113)
        .isNotEmpty()
        .allMatch(mutant -> mutant.skipped().isEmpty());
    assertThat(branches)
        .filteredOn(mutant -> mutant.line() == 107)
        .isNotEmpty()
        .allMatch(mutant -> mutant.skipped().orElse("").startsWith("generated: the Compose"));
  }

  /// A group's key, and the masks and stand-ins a composable call passes, are the compiler's: no
  /// number change runs on a line that's only a call to `Text` or `MaterialTheme`.
  @Test
  void theKeysMasksAndStandInsAPassedComposableCallTakesDontRun() {
    var numbers =
        running().stream()
            .filter(mutant -> mutant.operator().id().equals("mutastic/constant-value"))
            .filter(
                mutant ->
                    List.of("FocusApp", "TimerPanel", "TaskRow").contains(mutant.methodName()))
            .toList();

    assertThat(numbers).isEmpty();
  }

  /// `if (timer.running) "Pause" else "Start"`, in a composable lambda, is the person's.
  @Test
  void aComposableLambdasOwnCodeRuns() {
    assertThat(running())
        .anyMatch(
            mutant ->
                mutant.methodName().startsWith("TimerPanel$lambda")
                    && mutant.operator().id().equals("mutastic/remove-conditional"));
  }

  /// A lambda that returns nothing gives back `Unit`, which nothing reads.
  @Test
  void theUnitALambdaReturnsIsKotlincs() {
    assertThat(running())
        .noneMatch(
            mutant ->
                mutant.methodName().contains("$lambda")
                    && mutant.operator().id().equals("mutastic/return-value")
                    && mutant.replacement().equals("return null"));
  }
}
