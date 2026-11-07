package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.report.SourceView;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Each card a replaced method body gets says what its mutant does: see [ClaimFixture].
class MethodBodyTruthTest {
  private final ClaimFixture bodies =
      new ClaimFixture("bodies/Bodies.java", "mutastic/replace-body");

  @Test
  void aReplacedBodyIsTheMethodWithNothingButThatReturn() throws Exception {
    assertThat(bodies.running()).as("the cards checked").hasSizeGreaterThan(25);
    assertThat(bodies.claimsThatLie()).isEmpty();
  }

  /// Always `true` and always `false` are different gaps, and neither shows the other.
  @Test
  void aBooleanGetsBothValuesAndAnythingElseOne() {
    assertThat(bodies.changesIn("aTest")).containsExactly("body return false", "body return true");
    assertThat(bodies.changesIn("anInt")).containsExactly("body return 0");
    assertThat(bodies.changesIn("text")).containsExactly("body return \"\"");
    assertThat(bodies.changesIn("returnsNothing")).containsExactly("body emptied");
  }

  @Test
  void aValueItAlreadyGivesIsntOneOfTheChanges() {
    assertThat(bodies.changesIn("onlyFalse")).containsExactly("body return true");
    assertThat(bodies.changesIn("onlyZero")).containsExactly("body return 1");
  }

  @Test
  void aBodyThatsAlreadyOnlyThatIsEquivalent() throws Exception {
    assertThat(bodies.changesIn("onlyNull"))
        .containsExactly("body return null (equivalent: already returns null)");
    assertThat(bodies.changesIn("onlyAnEmptyList"))
        .containsExactly("body return List.of() (equivalent: already returns an empty list)");
    assertThat(bodies.changesIn("alreadyEmpty")).isEmpty();
    assertThat(bodies.skippedThatDiffer()).isEmpty();
  }

  @Test
  void codeThatWorksBeforeItsValueIsStillChanged() {
    assertThat(bodies.changesIn("worksThenEmpty")).containsExactly("body return List.of()");
  }

  @Test
  void trivialAccessorsAndDebugTextAreArid() throws Exception {
    var module = new ClaimFixture("bodies/Bodies.java", "mutastic/replace-body").prepared();
    assertThat(module.changesIn("getName"))
        .containsExactly("body return \"\" (arid: trivial getter of name)");
    assertThat(module.changesIn("toString")).hasSize(1).allMatch(change -> change.contains("arid"));
    assertThat(module.changesIn("hashCode")).hasSize(1).allMatch(change -> change.contains("arid"));
  }

  @Test
  void constructorsAndStaticInitialisersAreLeftAlone() {
    assertThat(bodies.mutants())
        .extracting(ClaimFixture::sourceMethod)
        .doesNotContain("<init>", "<clinit>");
  }

  @Test
  void aCardSaysTheMethodDoesNothingAndAsksForATestOfIt() {
    var sources = new SourceView(ClaimFixture.ROOT, List.of(ClaimFixture.ROOT));
    var value = only("anInt");
    assertThat(sources.advice(value).change()).isEqualTo("`anInt()` does nothing but return `0`");
    assertThat(sources.advice(value).headline())
        .isEqualTo("no test notices `anInt()` doing nothing but returning `0`");
    assertThat(sources.advice(value).fix())
        .isEqualTo("add a test that calls `anInt()` and asserts a result other than `0`");
    var nothing = only("returnsNothing");
    assertThat(sources.advice(nothing).change()).isEqualTo("`returnsNothing()` does nothing");
    assertThat(sources.advice(nothing).headline())
        .isEqualTo("no test notices `returnsNothing()` doing nothing");
    assertThat(sources.advice(nothing).fix())
        .isEqualTo("add a test that calls `returnsNothing()` and asserts what it does");
  }

  private io.mutastic.api.Mutant only(String method) {
    return bodies.running().stream()
        .filter(mutant -> mutant.methodName().equals(method))
        .findFirst()
        .orElseThrow();
  }
}
