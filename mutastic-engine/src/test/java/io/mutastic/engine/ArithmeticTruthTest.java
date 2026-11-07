package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.ClaimFixture.Inputs;
import io.mutastic.report.SourceView;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Each card a change to a calculation gets says what its mutant does: see [ClaimFixture].
class ArithmeticTruthTest {
  private final ClaimFixture increments =
      new ClaimFixture("arithmetic/Increments.java", "mutastic/flip-increment", Inputs.NUMBERS);
  private final ClaimFixture negations =
      new ClaimFixture("arithmetic/Negations.java", "mutastic/remove-negation", Inputs.NUMBERS);
  private final ClaimFixture others =
      new ClaimFixture(
          "arithmetic/OtherArithmetic.java", "mutastic/other-arithmetic", Inputs.NUMBERS);
  private final ClaimFixture kept =
      new ClaimFixture("arithmetic/KeptOperands.java", "mutastic/keep-operand", Inputs.NUMBERS);
  private final ClaimFixture constants =
      new ClaimFixture("arithmetic/ConstantValues.java", "mutastic/constant-value", Inputs.NUMBERS);
  private final ClaimFixture checked =
      new ClaimFixture("arithmetic/Checked.java", "library/unchecked-arithmetic", Inputs.NUMBERS);

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

  @Test
  void anOperatorBecomesTheOneItsCardNames() throws Exception {
    assertThat(others.running()).as("the cards checked").hasSizeGreaterThan(50);
    assertThat(others.claimsThatLie()).isEmpty();
  }

  /// `mutastic/math` and `mutastic/arithmetic-replacement` keep theirs, and so their ids.
  @Test
  void onlyWhatNoOtherOperatorMakesOfAWholeNumber() {
    assertThat(others.changesIn("anIntAdd")).containsExactly("IDIV", "IREM");
    assertThat(others.changesIn("anIntSub")).containsExactly("IMUL", "IDIV", "IREM");
    assertThat(others.changesIn("anIntMul")).containsExactly("IADD", "ISUB", "IREM");
    assertThat(others.changesIn("anIntDiv")).containsExactly("IADD", "ISUB", "IREM");
    assertThat(others.changesIn("aLongRem")).containsExactly("LADD", "LSUB", "LMUL", "LDIV");
    assertThat(others.changesIn("aFloatAdd")).containsExactly("FSUB", "FMUL", "FDIV", "FREM");
    assertThat(others.changesIn("aDoubleMul")).containsExactly("DADD", "DSUB", "DDIV", "DREM");
  }

  @Test
  void aCapacityIsAridAndOtherInstructionsArentArithmetic() {
    var why = " (arid: only sizes a collection's first allocation)";
    assertThat(others.changesIn("aCapacity")).containsExactly("IDIV" + why, "IREM" + why);
    assertThat(kept.changesIn("aCapacity")).containsExactly("keep first" + why);
    assertThat(others.mutants())
        .extracting(ClaimFixture::sourceMethod)
        .doesNotContain("aShift", "aMask", "aLongComparison");
    assertThat(kept.mutants())
        .extracting(ClaimFixture::sourceMethod)
        .doesNotContain("aShift", "aNegation");
  }

  @Test
  void anOtherOperatorsCardSaysWhichTwo() {
    var sources = sources();
    var symbols = java.util.Map.of("ADD", "+", "SUB", "-", "MUL", "*", "DIV", "/", "REM", "%");
    for (var mutant : others.running()) {
      var before = symbols.get(mutant.original().substring(1));
      var after = symbols.get(mutant.replacement().substring(1));
      var advice = sources.advice(mutant);
      assertThat(advice.change())
          .as(ClaimFixture.describe(mutant))
          .isEqualTo("`%s` becomes `%s`".formatted(before, after));
      assertThat(advice.headline()).contains("`%s` becomes `%s`".formatted(before, after));
    }
  }

  @Test
  void aKeptOperandIsThatValueAlone() throws Exception {
    assertThat(kept.running()).as("the cards checked").hasSizeGreaterThan(17);
    assertThat(kept.claimsThatLie()).isEmpty();
  }

  @Test
  void aConstantBesideAVariableIsNotWhatsKept() {
    assertThat(kept.changesIn("plusOne")).containsExactly("keep first");
    assertThat(kept.changesIn("twice")).containsExactly("keep second");
    assertThat(kept.changesIn("anIntSum")).containsExactly("keep first", "keep second");
  }

  @Test
  void aKeptOperandsCardNamesWhatNoTestNeeds() {
    var sources = sources();
    for (var mutant : kept.running()) {
      var operands = mutant.operands();
      if (ClaimFixture.sourceMethod(mutant).endsWith("Sum")) {
        boolean first = mutant.replacement().equals(Arithmetic.KEEP_FIRST);
        var whole = "`x + y`";
        var advice = sources.advice(mutant);
        assertThat(operands).containsExactly("x", "y");
        assertThat(advice.change())
            .isEqualTo("%s becomes `%s`".formatted(whole, first ? "x" : "y"));
        assertThat(advice.fix())
            .isEqualTo(
                "add a test where `%s` changes the result of %s, and assert it"
                    .formatted(first ? "y" : "x", whole));
      }
    }
  }

  @Test
  void aChangedConstantIsTheCodeWithThatValue() throws Exception {
    assertThat(constants.running()).as("the cards checked").hasSizeGreaterThan(80);
    assertThat(constants.valueClaimsThatLie()).isEmpty();
  }

  @Test
  void aConstantBecomesOneNextToItAndZeroOneAndMinusOneOnce() {
    assertThat(constants.changesIn("threshold"))
        .containsExactly(
            "constant 100 to 101",
            "constant 100 to 99",
            "constant 100 to 0",
            "constant 100 to 1",
            "constant 100 to -1");
    assertThat(constants.changesIn("plusOne"))
        .containsExactly("constant 1 to 2", "constant 1 to 0", "constant 1 to -1");
    assertThat(constants.changesIn("aLaterValue"))
        .containsExactly("constant 0 to 1", "constant 0 to -1");
    assertThat(constants.changesIn("aFloat"))
        .containsExactly(
            "constant 0.5 to 1.5",
            "constant 0.5 to -0.5",
            "constant 0.5 to 0.0",
            "constant 0.5 to 1.0",
            "constant 0.5 to -1.0");
    assertThat(constants.changesIn("aLongZero"))
        .containsExactly("constant 0 to 1", "constant 0 to -1");
  }

  /// Nothing can be one past the largest.
  @Test
  void aConstantDoesntWrapRound() {
    assertThat(constants.changesIn("theBiggest"))
        .contains("constant 2147483647 to 2147483646")
        .doesNotContain("constant 2147483647 to -2147483648");
  }

  /// The threshold set wrong that a default mutant can't show: the constant's own value is written
  /// where it's used. A test at the threshold tells them apart, and one far from it doesn't.
  @Test
  void aThresholdNamedByAConstantIsChangedAndATestNearItCatchesIt() throws Exception {
    var oneMore =
        constants.running().stream()
            .filter(mutant -> ClaimFixture.sourceMethod(mutant).equals("inlined"))
            .filter(mutant -> mutant.replacement().equals("constant 100 to 101"))
            .findFirst()
            .orElseThrow();
    assertThat(constants.outcomeOf(oneMore, 100, 0)).isEqualTo("under");
    assertThat(constants.outcomeOfOriginal("inlined", 100, 0)).isEqualTo("over");
    assertThat(constants.outcomeOf(oneMore, 5000, 0)).isEqualTo("over");
    assertThat(constants.outcomeOfOriginal("inlined", 5000, 0)).isEqualTo("over");
  }

  /// A `char` never becomes `-1`, where what uses it doesn't show it's a plain number.
  @Test
  void aValueStaysInTheTypeItCouldBe() {
    assertThat(constants.changesIn("aLetterGiven"))
        .containsExactly(
            "constant 98 to 99", "constant 98 to 97", "constant 98 to 0", "constant 98 to 1");
    assertThat(constants.changesIn("aFirstArgument"))
        .containsExactly(
            "constant 98 to 99", "constant 98 to 97", "constant 98 to 0", "constant 98 to 1");
  }

  @Test
  void whatOnlySizesOrWaitsIsArid() {
    assertThat(constants.changesIn("anArraySize"))
        .isNotEmpty()
        .allMatch(change -> change.endsWith("(arid: only sizes an array)"));
    assertThat(constants.changesIn("aCapacity"))
        .isNotEmpty()
        .allMatch(change -> change.endsWith("(arid: only sizes a collection's first allocation)"));
    assertThat(constants.changesIn("aWait"))
        .isNotEmpty()
        .allMatch(change -> change.endsWith("(arid: only sets how long to wait)"));
    assertThat(constants.changesIn("aSplitsLimit"))
        .contains("constant -1 to 0 (arid: only limits a split)");
    for (var start : List.of("aCounterFromZero", "aTotalFromOne", "aSumFromZero")) {
      assertThat(constants.changesIn(start))
          .as(start)
          .isNotEmpty()
          .allMatch(change -> change.endsWith("(arid: a variable's first value of 0 or 1)"));
    }
    assertThat(constants.changesIn("aLoopFromZero"))
        .contains("constant 0 to 1 (arid: a variable's first value of 0 or 1)");
  }

  @Test
  void whatIsntANumberTheSourceWroteIsLeftAlone() {
    assertThat(constants.mutants())
        .extracting(ClaimFixture::sourceMethod)
        .doesNotContain(
            "aTrue",
            "aFalseVariable",
            "aComparisonsResult",
            "aBooleanArgument",
            "comparedWithTrue",
            "aBooleanArray",
            "oneOrZero",
            "theComplement",
            "anArrayOfVariables",
            "varargs",
            "enhancedFor",
            "stringSwitch");
  }

  @Test
  void aConstantsCardNamesTheValueAndAsksForATestNearIt() {
    var sources = sources();
    for (var mutant : constants.running()) {
      var label = mutant.replacement().split(" ", 4);
      var advice = sources.advice(mutant);
      var quoted =
          ClaimFixture.sourceMethod(mutant).startsWith("aLetter")
              || ClaimFixture.sourceMethod(mutant).equals("aFirstArgument");
      if (!quoted) {
        assertThat(advice.change())
            .as(ClaimFixture.describe(mutant))
            .isEqualTo("the constant `%s` becomes `%s`".formatted(label[1], label[3]));
        assertThat(advice.headline()).contains("`%s` from `%s`".formatted(label[1], label[3]));
        assertThat(advice.fix()).contains("near where `%s` takes effect".formatted(label[1]));
      }
    }
  }

  @Test
  void aLettersCardSaysItAsTheSourceDoes() {
    var sources = sources();
    var letter =
        constants.running().stream()
            .filter(mutant -> ClaimFixture.sourceMethod(mutant).equals("aLetter"))
            .filter(mutant -> mutant.replacement().equals("constant 98 to 99"))
            .findFirst()
            .orElseThrow();
    var advice = sources.advice(letter);
    assertThat(advice.change()).isEqualTo("the constant `'b'` becomes `'c'`");
    assertThat(sources.mutatedLine(letter, advice)).contains("return (char) a == 'c';");
  }

  /// `LIMIT` in the source has nothing to rewrite, and the number where it's written does.
  @Test
  void theLineIsRewrittenOnlyWhereItHasTheNumber() {
    var sources = sources();
    for (var mutant : constants.running()) {
      if (mutant.replacement().equals("constant 100 to 101")) {
        var line = sources.mutatedLine(mutant, sources.advice(mutant));
        if (ClaimFixture.sourceMethod(mutant).equals("threshold")) {
          assertThat(line).contains("return a >= 101 ? \"over\" : \"under\";");
        } else {
          assertThat(line).as(ClaimFixture.describe(mutant)).isEmpty();
        }
      }
    }
  }

  /// `claimsThatLie` fails a mutant that changes nothing, so every one of these overflowed.
  @Test
  void aCheckedCallIsThePlainOperationAndItsInputsOverflow() throws Exception {
    assertThat(checked.running()).as("the cards checked").hasSizeGreaterThan(17);
    assertThat(checked.claimsThatLie()).isEmpty();
  }

  @Test
  void anOverflowThrowsTodayAndWrapsRoundWithTheMutant() throws Exception {
    var add =
        checked.running().stream()
            .filter(mutant -> ClaimFixture.sourceMethod(mutant).equals("anAdd"))
            .findFirst()
            .orElseThrow();
    assertThat(checked.outcomeOfOriginal("anAdd", Integer.MAX_VALUE, 1))
        .isEqualTo("threw ArithmeticException");
    assertThat(checked.outcomeOf(add, Integer.MAX_VALUE, 1))
        .isEqualTo(String.valueOf(Integer.MIN_VALUE));
    assertThat(checked.outcomeOfOriginal("anAdd", 2, 3)).isEqualTo("5");
    assertThat(checked.outcomeOf(add, 2, 3)).isEqualTo("5");
  }

  @Test
  void onlyTheExactMethodsAreChanged() {
    assertThat(checked.mutants())
        .extracting(ClaimFixture::sourceMethod)
        .doesNotContain("anAbsolute", "aFloorDivision", "big");
  }

  @Test
  void aCheckedCallsCardAsksForAnOverflow() {
    var sources = sources();
    for (var mutant : checked.running()) {
      var called = CallRule.calledName(mutant.original());
      var advice = sources.advice(mutant);
      assertThat(advice.change()).isEqualTo("no longer throws on overflow");
      assertThat(advice.headline()).startsWith("no test makes `%s` overflow in ".formatted(called));
      assertThat(advice.fix()).contains("doesn't fit its type").contains("ArithmeticException");
    }
  }
}
