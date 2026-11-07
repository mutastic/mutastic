package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.report.SourceView;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Each card a change to a call gets says what its mutant does: see [ClaimFixture].
class CallTruthTest {
  private final ClaimFixture omitted = new ClaimFixture("calls/Omitted.java", "mutastic/omit-call");
  private final ClaimFixture swapped =
      new ClaimFixture("calls/Swapped.java", "mutastic/swap-arguments");
  private final ClaimFixture nulled =
      new ClaimFixture("calls/NullParameters.java", "mutastic/null-parameter");
  private final ClaimFixture dropped =
      new ClaimFixture("calls/Dropped.java", "mutastic/drop-vararg");
  private final ClaimFixture shorter =
      new ClaimFixture("calls/LessArguments.java", "library/omit-argument");

  @Test
  void aNullParameterIsTheMethodWithItSetToNullAsItStarts() throws Exception {
    assertThat(nulled.running()).as("the cards checked").hasSizeGreaterThan(9);
    assertThat(nulled.claimsThatLie()).isEmpty();
  }

  @Test
  void aParameterNothingReadsIsSkippedAsEquivalent() throws Exception {
    var why = " (equivalent: nothing reads the value it comes in with)";
    assertThat(nulled.changesIn("neverRead"))
        .containsExactly("null parameter 0 a" + why, "null parameter 1 b");
    assertThat(nulled.changesIn("assignedFirst"))
        .containsExactly("null parameter 0 a" + why, "null parameter 1 b");
    assertThat(nulled.skippedThatDiffer()).isEmpty();
  }

  @Test
  void aParameterIsFoundInItsSlotPastWideOnes() {
    assertThat(nulled.changesIn("withPrimitives"))
        .containsExactly("null parameter 2 one", "null parameter 4 other");
    assertThat(nulled.changesIn("onAnInstance"))
        .containsExactly("null parameter 1 a", "null parameter 2 b");
  }

  @Test
  void theSlotInTheLabelIsTheParameterThatsNull() throws Exception {
    var other =
        nulled.running().stream()
            .filter(mutant -> mutant.replacement().equals("null parameter 4 other"))
            .findFirst()
            .orElseThrow();

    assertThat(ClaimFixture.outcome(nulled.mutated(other), "wideAndNarrowFirst", "x", "y"))
        .isEqualTo("1x2null");
  }

  @Test
  void aNullParametersCardNamesIt() {
    var sources = new SourceView(ClaimFixture.ROOT, List.of(ClaimFixture.ROOT));
    for (var mutant : nulled.running()) {
      var name = mutant.replacement().substring(mutant.replacement().lastIndexOf(' ') + 1);
      var advice = sources.advice(mutant);
      assertThat(advice.change()).isEqualTo("`%s` is `null` from the start of the method", name);
      assertThat(advice.headline()).contains("`" + name + "`");
      assertThat(advice.fix()).contains("`" + name + "`");
    }
  }

  @Test
  void aDroppedElementIsTheCallWithoutItsLastValue() throws Exception {
    assertThat(dropped.running()).as("the cards checked").hasSizeGreaterThan(8);
    assertThat(dropped.claimsThatLie()).isEmpty();
  }

  @Test
  void onlyAnArrayBuiltForTheCallWithSomethingInItLosesAnElement() {
    assertThat(dropped.mutants())
        .extracting(ClaimFixture::sourceMethod)
        .doesNotContain("noneGiven", "anExistingArray", "allocatedOnly", "theValueOfAnAssignment");
  }

  @Test
  void aDroppedElementsCardNamesTheCall() {
    var sources = new SourceView(ClaimFixture.ROOT, List.of(ClaimFixture.ROOT));
    for (var mutant : dropped.running()) {
      var called = "`%s()`".formatted(CallRule.calledName(mutant.original()));
      var advice = sources.advice(mutant);
      assertThat(advice.change())
          .isEqualTo("the last value in the array given to %s is left out", called);
      assertThat(advice.headline()).contains(called);
      assertThat(advice.fix()).contains(called);
    }
  }

  @Test
  void anOmittedArgumentIsTheShorterOverload() throws Exception {
    assertThat(shorter.running()).as("the cards checked").hasSizeGreaterThan(9);
    assertThat(shorter.claimsThatLie()).isEmpty();
    assertThat(shorter.mutants())
        .extracting(ClaimFixture::sourceMethod)
        .doesNotContain(
            "threeArguments",
            "theOneArgumentSubstring",
            "theOneArgumentToString",
            "aSplitLimit",
            "aLocale");
  }

  @Test
  void everyOverloadAnArgumentIsOmittedFromHasTheShorterOne() throws Exception {
    var rule =
        CallRules.BUILT_IN.stream()
            .filter(each -> each.id().equals("library/omit-argument"))
            .findFirst()
            .orElseThrow();
    int matched = 0;
    for (String owner : rule.owners()) {
      var type = Class.forName(owner);
      for (var method : type.getMethods()) {
        var shape =
            java.lang.invoke.MethodType.methodType(
                method.getReturnType(), method.getParameterTypes());
        if (rule.methods().contains(method.getName())
            && rule.descriptors().contains(shape.toMethodDescriptorString())) {
          matched++;
          var parameters = method.getParameterTypes();
          var fewer = java.util.Arrays.copyOf(parameters, parameters.length - 1);
          var other = type.getMethod(method.getName(), fewer);
          assertThat(other.getReturnType()).as(method.toString()).isEqualTo(method.getReturnType());
          assertThat(java.lang.reflect.Modifier.isStatic(other.getModifiers()))
              .as(method.toString())
              .isEqualTo(java.lang.reflect.Modifier.isStatic(method.getModifiers()));
        }
      }
    }
    assertThat(matched).as("the overloads checked").isGreaterThan(9);
  }

  @Test
  void swappedArgumentsAreTheCallWithThemTheOtherWayRound() throws Exception {
    assertThat(swapped.running()).as("the cards checked").hasSizeGreaterThan(9);
    assertThat(swapped.claimsThatLie()).isEmpty();
  }

  @Test
  void onlyArgumentsOfOneRealTypeAreSwapped() {
    assertThat(swapped.mutants())
        .extracting(ClaimFixture::sourceMethod)
        .doesNotContain("differentTypes", "objects", "anEarlierPair", "oneArgument");
  }

  @Test
  void aSwapThatCantChangeTheResultIsSkippedAsEquivalent() throws Exception {
    var swap = SwappedArguments.SWAP;
    assertThat(swapped.changesIn("aMax"))
        .containsExactly(swap + " (equivalent: `Math.max` gives the same either way round)");
    assertThat(swapped.changesIn("aSum"))
        .containsExactly(swap + " (equivalent: `Integer.sum` gives the same either way round)");
    assertThat(swapped.changesIn("aHypotenuse"))
        .containsExactly(swap + " (equivalent: `Math.hypot` gives the same either way round)");
    assertThat(swapped.changesIn("theSameVariableTwice"))
        .containsExactly(swap + " (equivalent: both arguments are the same value)");
    assertThat(swapped.changesIn("theSameConstantTwice"))
        .containsExactly(swap + " (equivalent: both arguments are the same value)");
    assertThat(swapped.skippedThatDiffer()).isEmpty();
  }

  @Test
  void aSwapsCardSaysWhichCallAndWhatToTest() {
    var sources = new SourceView(ClaimFixture.ROOT, List.of(ClaimFixture.ROOT));
    for (var mutant : swapped.running()) {
      var called = "`%s()`".formatted(CallRule.calledName(mutant.original()));
      var advice = sources.advice(mutant);
      assertThat(advice.change())
          .as(ClaimFixture.describe(mutant))
          .isEqualTo("the last two arguments of %s swap places".formatted(called));
      assertThat(advice.headline()).contains(called);
      assertThat(advice.fix()).contains(called);
    }
  }

  @Test
  void anOmittedCallIsLeftOutAndWhatItWasGivenGoesOn() throws Exception {
    assertThat(omitted.running()).as("the cards checked").hasSizeGreaterThan(9);
    assertThat(omitted.claimsThatLie()).isEmpty();
  }

  @Test
  void anOmittedCallKeepsWhatTheCallCanOnlyReturn() {
    assertThat(omitted.changesIn("trimmed")).containsExactly("keep receiver");
    assertThat(omitted.changesIn("aBuilderStep")).containsExactly("keep receiver");
    assertThat(omitted.changesIn("anAbsoluteInt")).containsExactly("keep argument");
    assertThat(omitted.changesIn("aStaticWrapper")).containsExactly("keep argument");
    assertThat(omitted.changesIn("anInstanceWrapper")).containsExactly("keep argument");
  }

  @Test
  void onlyCallsItCanBeSureOfAreOmitted() {
    assertThat(omitted.mutants())
        .extracting(ClaimFixture::sourceMethod)
        .doesNotContain(
            "discarded",
            "callsAGenericMethod",
            "callsABoundedGenericMethod",
            "twoToChooseFrom",
            "aLibrarysMethod",
            "aLong");
  }

  @Test
  void anOmittedCallsCardSaysWhatGoesOnInItsPlace() {
    var sources = new SourceView(ClaimFixture.ROOT, List.of(ClaimFixture.ROOT));
    for (var mutant : omitted.running()) {
      var called = "`%s()`".formatted(CallRule.calledName(mutant.original()));
      var kept =
          mutant.replacement().equals(Wrappers.KEEP_RECEIVER)
              ? "what it's called on"
              : "what it's given";
      var advice = sources.advice(mutant);
      assertThat(advice.change())
          .as(ClaimFixture.describe(mutant))
          .isEqualTo("%s is left out, so %s goes on in its place".formatted(called, kept));
      assertThat(advice.headline()).contains("no test needs " + called);
      assertThat(advice.fix()).contains(called);
    }
  }
}
