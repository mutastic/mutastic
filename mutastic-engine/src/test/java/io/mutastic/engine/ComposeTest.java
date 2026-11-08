package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import java.io.IOException;
import java.lang.classfile.ClassFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/// What the Compose compiler writes into a `@Composable`, told from what the person wrote there, on
/// real output: `FocusAppKt.class` and its lambdas' singleton, as Kotlin 2.2.21 and Compose
/// Multiplatform 1.9.3 compile `samples/compose`'s `FocusApp.kt`. Kept as class files, so nothing
/// is compiled here; when the sample's `FocusApp.kt` changes, copy them again from its
/// `build/classes/kotlin/main`.
class ComposeTest {
  private static final Path KEPT = Path.of("src/test/resources/kotlin/compose");

  /// The mutants of `operators` in the kept classes. Each test finds its own, with only the
  /// operators its claim is about: a mutant is run against every test that reaches its line, and a
  /// `@BeforeAll` that found every operator's would put all five in front of every mutant the
  /// scanning reaches.
  private static List<Mutant> mutants(String... operators) throws IOException {
    var context = ClassFile.of();
    var models =
        List.of(
            context.parse(Files.readAllBytes(KEPT.resolve("FocusAppKt.class"))),
            context.parse(
                Files.readAllBytes(KEPT.resolve("ComposableSingletons$FocusAppKt.class"))));
    var mutations =
        new Mutations(
            context, new Policy(List.of(), List.of(), List.of(), List.of(operators), List.of()));
    mutations.prepare(models);
    return models.stream().flatMap(model -> mutations.find(model).stream()).toList();
  }

  private static List<Mutant> running(String... operators) throws IOException {
    return mutants(operators).stream().filter(mutant -> mutant.skipped().isEmpty()).toList();
  }

  /// Every call to the runtime's bookkeeping: groups, `remember`'s cache, `changed`, skipping,
  /// trace and source information, the restart scope.
  @Test
  void noCallToTheComposeRuntimeRuns() throws IOException {
    assertThat(running("all"))
        .noneMatch(mutant -> mutant.original().contains(" androidx/compose/runtime/Composer."))
        .noneMatch(mutant -> mutant.original().contains(" androidx/compose/runtime/ComposerKt."))
        .noneMatch(mutant -> mutant.original().contains("ScopeUpdateScope."))
        .noneMatch(mutant -> mutant.original().contains("ComposableLambdaKt."));
  }

  /// `Summary`'s `when` is the person's; its `$changed` mask and its skipping aren't. In the source
  /// the function starts at line 107 and its `when` is on lines 110 to 113.
  @Test
  void aComposablesOwnBranchesRunAndItsMasksDont() throws IOException {
    var branches =
        mutants("mutastic/remove-conditional").stream()
            .filter(mutant -> mutant.methodName().equals("Summary"))
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
  void theKeysMasksAndStandInsAPassedComposableCallTakesDontRun() throws IOException {
    var numbers =
        running("mutastic/constant-value").stream()
            .filter(
                mutant ->
                    List.of("FocusApp", "TimerPanel", "TaskRow").contains(mutant.methodName()))
            .toList();

    assertThat(numbers).isEmpty();
  }

  /// `if (timer.running) "Pause" else "Start"`, in a composable lambda, is the person's.
  @Test
  void aComposableLambdasOwnCodeRuns() throws IOException {
    assertThat(running("mutastic/remove-conditional"))
        .anyMatch(mutant -> mutant.methodName().startsWith("TimerPanel$lambda"));
  }

  /// A lambda that returns nothing gives back `Unit`, which nothing reads.
  @Test
  void theUnitALambdaReturnsIsKotlincs() throws IOException {
    assertThat(running("mutastic/return-value"))
        .noneMatch(
            mutant ->
                mutant.methodName().contains("$lambda")
                    && mutant.replacement().equals("return null"));
  }
}
