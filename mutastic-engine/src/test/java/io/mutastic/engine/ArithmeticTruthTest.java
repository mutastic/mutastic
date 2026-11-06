package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.ClaimFixture.Inputs;
import io.mutastic.report.SourceView;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// Each card a change to a calculation gets says what its mutant does: see [ClaimFixture].
class ArithmeticTruthTest {
  private static ClaimFixture increments;
  private static ClaimFixture negations;

  @BeforeAll
  static void compile() throws Exception {
    increments =
        new ClaimFixture("arithmetic/Increments.java", "mutastic/flip-increment", Inputs.NUMBERS);
    negations =
        new ClaimFixture("arithmetic/Negations.java", "mutastic/remove-negation", Inputs.NUMBERS);
  }

  private static SourceView sources() {
    return new SourceView(ClaimFixture.ROOT, List.of(ClaimFixture.ROOT));
  }

  @Test
  void aFlippedStepGoesTheOtherWay() throws Exception {
    assertThat(increments.running()).as("the cards checked").hasSizeGreaterThan(11);
    assertThat(increments.claimsThatLie()).isEmpty();
  }

  @Test
  void aFlippedStepNamesTheVariableAndWhatItNowTakes() {
    assertThat(increments.changesIn("up")).containsExactly("x by -1");
    assertThat(increments.changesIn("byMore")).containsExactly("x by -5");
    assertThat(increments.changesIn("wide")).containsExactly("x by 1000");
    assertThat(increments.changesIn("aLoop")).containsExactly("i by -1");
    assertThat(increments.changesIn("inAFinally")).containsExactly("x by -1");
  }

  @Test
  void aStepNothingReadsIsSkippedAsEquivalent() throws Exception {
    var why = " (equivalent: nothing reads the value it leaves)";
    assertThat(increments.changesIn("neverRead")).containsExactly("x by -1" + why);
    assertThat(increments.changesIn("overwritten")).containsExactly("x by -1" + why);
    assertThat(increments.skippedThatDiffer()).isEmpty();
  }

  @Test
  void onlyASourceVariablesOwnStepIsFlipped() {
    assertThat(increments.mutants())
        .extracting(ClaimFixture::sourceMethod)
        .doesNotContain(
            "enhancedFor",
            "aLong",
            "aByte",
            "writtenOut",
            "byAVariable",
            "byNothing",
            "theSmallestStep");
  }

  @Test
  void aFlippedStepsCardNamesTheVariableAndTheWayItShouldGo() {
    var sources = sources();
    for (var mutant : increments.running()) {
      var label = mutant.replacement().split(" ", 3);
      var name = "`%s`".formatted(label[0]);
      int step = Integer.parseInt(label[2]);
      var today = step < 0 ? "up" : "down";
      var advice = sources.advice(mutant);
      assertThat(advice.change())
          .as(ClaimFixture.describe(mutant))
          .isEqualTo(
              "%s goes %s by %d instead of %s"
                  .formatted(name, step < 0 ? "down" : "up", Math.abs(step), today));
      assertThat(advice.headline()).contains("no test needs %s to go %s".formatted(name, today));
      assertThat(advice.fix()).contains("%s going %s".formatted(name, today));
    }
  }

  @Test
  void aRemovedNegationIsTheValueWithoutItsMinusSign() throws Exception {
    assertThat(negations.running()).as("the cards checked").hasSizeGreaterThan(10);
    assertThat(negations.claimsThatLie()).isEmpty();
  }

  @Test
  void onlyANegationInTheCodeIsRemoved() {
    assertThat(negations.mutants())
        .extracting(ClaimFixture::sourceMethod)
        .doesNotContain("aNegativeLiteral", "aNegatedConstant", "aSubtraction", "aComplement");
  }

  @Test
  void aRemovedNegationsCardAsksForTheSign() {
    var sources = sources();
    for (var mutant : negations.running()) {
      var advice = sources.advice(mutant);
      assertThat(advice.change()).isEqualTo("a minus sign is removed, so the value keeps its sign");
      assertThat(advice.headline()).contains("no test checks the sign");
      assertThat(advice.fix()).contains("isn't zero");
    }
  }
}
