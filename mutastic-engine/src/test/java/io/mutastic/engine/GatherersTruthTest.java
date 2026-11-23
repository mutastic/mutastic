package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.engine.ClaimFixture.Inputs;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Java 24's gatherers: each card a window or a fold swapped for its pair gets says what its mutant
/// does (see [ClaimFixture]), and a gatherer's numbers are skipped as arid only where the mutant
/// changes only how many calls run at once, each mutant run to show it. A size made less than one
/// always throws, so any test that reaches it catches it: it's counted.
class GatherersTruthTest {
  private static final String AT_ONCE = " (arid: only sets how many run at once)";

  /// The sizes made less than one, by method: each counted, and each throws for every input.
  private static final java.util.Set<String> ALWAYS_THROW =
      java.util.Set.of(
          "windowsInline constant 3 to 0",
          "windowsInline constant 3 to -1",
          "slidingInALocal constant 2 to 0",
          "slidingInALocal constant 2 to -1",
          "chained constant 2 to 0",
          "chained constant 2 to -1",
          "chained constant 3 to 0",
          "concurrently constant 4 to 0",
          "concurrentlyCapturing constant 8 to 0",
          "concurrentlyByReference constant 2 to 0");

  /// Inputs either side of zero and of the windows' sizes, and an end of `int`.
  private static final List<Integer> SOME = List.of(Integer.MIN_VALUE, -1, 0, 1, 2, 3, 7);

  private final ClaimFixture windows =
      new ClaimFixture("streams/Gathering.java", "stream/swap-window", Inputs.NUMBERS);
  private final ClaimFixture folds =
      new ClaimFixture("streams/Gathering.java", "stream/swap-fold-scan", Inputs.NUMBERS);
  private final ClaimFixture sizes =
      new ClaimFixture("streams/Sizes.java", "mutastic/constant-value", Inputs.NUMBERS);

  @Test
  void aWindowSwappedIsWhatTheCardSays() throws Exception {
    assertThat(windows.running()).as("the cards checked").hasSize(5);
    assertThat(windows.claimsThatLie()).isEmpty();
    assertThat(windows.changesIn("windowsOfOne"))
        .containsExactly(
            "windowSliding (equivalent: `windowFixed(1)` and `windowSliding(1)` both put each"
                + " element in a window of its own)");
    assertThat(windows.skippedThatDiffer()).isEmpty();
  }

  @Test
  void aFoldSwappedIsWhatTheCardSays() throws Exception {
    assertThat(folds.running()).as("the cards checked").hasSize(4);
    assertThat(folds.claimsThatLie()).isEmpty();
  }

  @Test
  void aWindowsSizeIsCounted() {
    assertThat(changes("windowsInline", 3))
        .containsExactly(
            "constant 3 to 4",
            "constant 3 to 2",
            "constant 3 to 0",
            "constant 3 to 1",
            "constant 3 to -1");
    assertThat(changes("slidingInALocal", 2))
        .containsExactly(
            "constant 2 to 3", "constant 2 to 1", "constant 2 to 0", "constant 2 to -1");
    assertThat(changes("chained", 2))
        .containsExactly(
            "constant 2 to 3", "constant 2 to 1", "constant 2 to 0", "constant 2 to -1");
  }

  /// Every change to how many run at once, in each shape: a lambda, one that captures a value, a
  /// method reference on an object and after another gatherer. `-1` is no `char`, so a number not
  /// shown to be an `int` isn't changed to it.
  @Test
  void howManyRunAtOnceIsArid() {
    assertThat(changes("concurrently", 4))
        .containsExactly(
            "constant 4 to 5" + AT_ONCE,
            "constant 4 to 3" + AT_ONCE,
            "constant 4 to 0",
            "constant 4 to 1" + AT_ONCE);
    assertThat(changes("concurrentlyCapturing", 8))
        .containsExactly(
            "constant 8 to 9" + AT_ONCE,
            "constant 8 to 7" + AT_ONCE,
            "constant 8 to 0",
            "constant 8 to 1" + AT_ONCE);
    assertThat(changes("concurrentlyByReference", 2))
        .containsExactly(
            "constant 2 to 3" + AT_ONCE, "constant 2 to 1" + AT_ONCE, "constant 2 to 0");
    assertThat(changes("chained", 3))
        .containsExactly(
            "constant 3 to 4" + AT_ONCE,
            "constant 3 to 2" + AT_ONCE,
            "constant 3 to 0",
            "constant 3 to 1" + AT_ONCE);
  }

  /// A number that isn't the size itself, but goes into it, and the numbers beside a gatherer's,
  /// are counted.
  @Test
  void onlyHowManyRunAtOnceIsArid() {
    assertThat(sizes.changesIn("sizeFromArithmetic"))
        .isNotEmpty()
        .noneMatch(GatherersTruthTest::arid);
    assertThat(sizes.changesIn("sizeAtLeast")).isNotEmpty().noneMatch(GatherersTruthTest::arid);
    assertThat(changes("windowsInline", 7)).isNotEmpty().noneMatch(GatherersTruthTest::arid);
    assertThat(changes("concurrentlyByReference", 4))
        .isNotEmpty()
        .noneMatch(GatherersTruthTest::arid);
  }

  /// The changes to one number in a method.
  private List<String> changes(String method, int number) {
    return sizes.changesIn(method).stream()
        .filter(change -> change.startsWith("constant " + number + " to "))
        .toList();
  }

  /// Run: each size made less than one is counted and throws, for every input; each skipped as only
  /// setting how many run at once gives what the code does; each other counted one changes what it
  /// gives.
  @Test
  void eachSkipIsWhatItSays() throws Exception {
    var failures = new ArrayList<String>();
    for (Mutant mutant : sizes.mutants()) {
      var why = mutant.skipped().map(reason -> " (" + reason + ")").orElse("");
      var method = ClaimFixture.sourceMethod(mutant);
      var throwing = ALWAYS_THROW.contains(method + " " + mutant.replacement());
      if (throwing && !why.isEmpty()) {
        failures.add(ClaimFixture.describe(mutant) + " always throws, and is skipped" + why);
      }
      var differs = false;
      for (int a : SOME) {
        for (int b : SOME) {
          var mutated = sizes.outcomeOf(mutant, a, b);
          var original = sizes.outcomeOfOriginal(method, a, b);
          differs |= !mutated.equals(original);
          if (throwing && !mutated.equals("threw IllegalArgumentException")) {
            failures.add(ClaimFixture.describe(mutant) + " gives " + mutated);
          }
          if (why.equals(AT_ONCE) && !mutated.equals(original)) {
            failures.add(ClaimFixture.describe(mutant) + " gives " + mutated + ", not " + original);
          }
        }
      }
      if (why.isEmpty() && !differs) {
        failures.add(ClaimFixture.describe(mutant) + " is counted and changes nothing");
      }
    }
    assertThat(failures).isEmpty();
    assertThat(sizes.mutants())
        .filteredOn(
            mutant ->
                ALWAYS_THROW.contains(
                    ClaimFixture.sourceMethod(mutant) + " " + mutant.replacement()))
        .as("the sizes made less than one")
        .hasSize(ALWAYS_THROW.size());
  }

  private static boolean arid(String change) {
    return change.endsWith(AT_ONCE);
  }
}
