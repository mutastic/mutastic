package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.ClaimFixture.Inputs;
import io.mutastic.report.SourceView;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// Each card a removed switch arm gets says what its mutant does, on every shape javac compiles a
/// switch to: see [ClaimFixture].
class SwitchArmTruthTest {
  private static ClaimFixture arms;

  @BeforeAll
  static void compile() throws Exception {
    arms = new ClaimFixture("switches/Arms.java", "mutastic/remove-switch-arm", Inputs.NUMBERS);
  }

  @Test
  void aRemovedArmIsTheSwitchWithoutIt() throws Exception {
    assertThat(arms.running()).as("the cards checked").hasSizeGreaterThan(20);
    assertThat(arms.claimsThatLie()).isEmpty();
  }

  @Test
  void aStringSwitchsHashLookupHasNoArms() {
    assertThat(arms.changesIn("aString")).containsExactly("arm 0 \"1\"");
    assertThat(arms.changesIn("aStringArrow")).containsExactly("arm 0 \"2\"");
  }

  @Test
  void anEnumsNumberingIsNotAName() {
    assertThat(arms.changesIn("anEnum")).containsExactly("arm 0");
    assertThat(arms.changesIn("aMappedEnum")).containsExactly("arm 1");
  }

  /// Found on mutastic's own code: its bootstrap's name, `invoke`, was taken for the constant's.
  @Test
  void aConstantInAPatternSwitchIsNamedForItself() {
    assertThat(arms.changesIn("aQualifiedConstant"))
        .containsExactly("throwing arm 0 LOW", "throwing arm 1 HIGH", "throwing arm 2 Other");
  }

  @Test
  void aDefaultOfTheSourcesOwnIsNotJavacs() {
    assertThat(arms.changesIn("aMappedEnumWithAThrow")).containsExactly("arm 1", "arm 2");
  }

  @Test
  void aSwitchWithNoDefaultOfItsOwnThrows() throws Exception {
    assertThat(arms.changesIn("anExhaustiveEnum"))
        .containsExactly("throwing arm 0", "throwing arm 1", "throwing arm 2");
    assertThat(arms.changesIn("aSealedType"))
        .containsExactly("throwing arm 0 Circle", "throwing arm 1 Square");
    var circle =
        arms.running().stream()
            .filter(mutant -> mutant.replacement().equals("throwing arm 0 Circle"))
            .findFirst()
            .orElseThrow();
    assertThat(arms.outcomeOfOriginal("aSealedType", 1, 2)).isEqualTo("3");
    assertThat(arms.outcomeOf(circle, 1, 2)).isEqualTo("threw MatchException");
    assertThat(arms.outcomeOf(circle, -1, 2)).isEqualTo("-3");
  }

  @Test
  void aGapInATableIsNoArm() {
    assertThat(arms.changesIn("aTableWithGaps")).containsExactly("arm 1 1", "arm 3 3");
  }

  @Test
  void anArmThatGoesWhereTheDefaultGoesIsEquivalent() throws Exception {
    assertThat(arms.changesIn("anEmptyArm"))
        .containsExactly(
            "arm 1 1 (equivalent: the arm goes straight to where the default goes)", "arm 2 2");
    assertThat(arms.skippedThatDiffer()).isEmpty();
  }

  @Test
  void aCardIsOnTheArmsOwnLineAndNamesIt() {
    var sources = new SourceView(ClaimFixture.ROOT, List.of(ClaimFixture.ROOT));
    assertThat(sources.line(only("arm 0 \"1\""))).hasValue("return b;");
    assertThat(sources.advice(only("arm 0 \"1\"")).headline())
        .isEqualTo("no test needs `case \"1\"` in `aString()`");
    assertThat(sources.advice(only("arm 97 97")).change())
        .isEqualTo("`case 'a'` is removed, so its values go to the default");
    assertThat(sources.advice(only("arm 0")).change())
        .isEqualTo("this arm is removed, so its values go to the default");
    assertThat(sources.line(only("throwing arm 0 Circle"))).hasValue("case Circle c -> c.r() + b;");
    assertThat(sources.advice(only("throwing arm 0 Circle")).change())
        .isEqualTo("`case Circle` is removed, so a value that reached it throws");
    assertThat(sources.advice(only("throwing arm 0 Circle")).fix())
        .isEqualTo("add a test with a value that reaches `case Circle`, and assert what it does");
  }

  private static io.mutastic.api.Mutant only(String label) {
    return arms.running().stream()
        .filter(mutant -> mutant.replacement().equals(label))
        .findFirst()
        .orElseThrow();
  }
}
