package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// A close written by hand in a method with a try-with-resources, or with its own `addSuppressed`,
/// is the person's: its mutants run, each changes what the method gives, and each card's lines are
/// what the mutant does. What javac writes to close the resource is skipped. See [ClaimFixture].
class CloseTruthTest {
  private static final String FILE = "resources/Closes.java";
  private static final String REASON = " (" + TryWithResources.REASON + ")";
  private static final List<String> BY_HAND = List.of("besideOne", "closedTwice", "byHand");

  private final ClaimFixture removed = new ClaimFixture(FILE, "mutastic/remove-void-call");
  private final ClaimFixture checks = new ClaimFixture(FILE, "mutastic/remove-conditional");

  @Test
  void javacsCloseIsSkipped() {
    assertThat(removed.changesIn("single"))
        .containsExactly("remove call" + REASON, "remove call" + REASON, "remove call" + REASON);
    assertThat(removed.changesIn("two")).hasSize(6).allMatch(change -> change.endsWith(REASON));
    assertThat(checks.changesIn("single")).hasSize(4).allMatch(change -> change.endsWith(REASON));
    assertThat(checks.changesIn("fromNew")).isEmpty();
    assertThat(checks.changesIn("exits"))
        .as("the person's `if` in the body runs")
        .filteredOn(change -> !change.endsWith(REASON))
        .containsExactly("always jump", "never jump");
  }

  @Test
  void aCloseByHandRuns() {
    assertThat(removed.changesIn("besideOne"))
        .endsWith("remove call")
        .containsOnlyOnce("remove call");
    assertThat(removed.changesIn("closedTwice"))
        .filteredOn(change -> !change.endsWith(REASON))
        .containsExactly("remove call", "remove call");
    assertThat(removed.changesIn("byHand"))
        .containsExactly("remove call", "remove call", "remove call");
    assertThat(checks.changesIn("besideOne")).endsWith("always jump", "never jump");
    assertThat(checks.changesIn("byHand"))
        .containsExactly("always jump", "never jump", "always jump", "never jump");
  }

  /// Each would be caught by a test of what the method gives, as taking the close out by hand is.
  @Test
  void eachChangesWhatTheMethodGives() throws Exception {
    var unchanged = new ArrayList<String>();
    for (var fixture : List.of(removed, checks)) {
      for (var mutant : fixture.running()) {
        if (BY_HAND.contains(mutant.methodName()) && !changesSomething(fixture, mutant)) {
          unchanged.add(ClaimFixture.describe(mutant));
        }
      }
    }
    assertThat(unchanged).isEmpty();
  }

  @Test
  void eachCardShowsWhatItsMutantDoes() throws Exception {
    var shown = new ArrayList<String>();
    assertThat(removed.shownChangesThatAreNotTheMutants(shown)).isEmpty();
    assertThat(checks.shownChangesThatAreNotTheMutants(shown)).isEmpty();
    for (var method : BY_HAND) {
      assertThat(shown).as(method).anyMatch(card -> card.startsWith(method + " line "));
    }
    assertThat(shown)
        .anyMatch(card -> card.startsWith("besideOne ") && card.endsWith(": if (true) {"))
        .anyMatch(card -> card.startsWith("byHand ") && card.endsWith(": if (false) {"));
  }

  private static boolean changesSomething(ClaimFixture fixture, Mutant mutant)
      throws ReflectiveOperationException {
    for (var a : ClaimFixture.Inputs.TEXTS.values()) {
      for (var b : List.of("a", "b", "ab", "1")) {
        if (!fixture
            .outcomeOf(mutant, a, b)
            .equals(fixture.outcomeOfOriginal(mutant.methodName(), a, b))) {
          return true;
        }
      }
    }
    return false;
  }
}
