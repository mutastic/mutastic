package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.ClaimFixture.Inputs;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/// The lines a card shows as the mutant has them are the mutant's: written into the method, they
/// give the code of the claim beside it, which each fixture's own truth test holds to what the
/// mutant does; or, compiled and run, they do what the mutant does on every input.
class ShownChangeTruthTest {
  /// Each fixture with claims, and the operators whose changes it writes out.
  private record Fixture(String file, String operator, Inputs inputs) {
    Fixture(String file, String operator) {
      this(file, operator, Inputs.TEXTS);
    }
  }

  private static final List<Fixture> FIXTURES =
      List.of(
          new Fixture("arithmetic/Increments.java", "mutastic/flip-increment", Inputs.NUMBERS),
          new Fixture("arithmetic/Negations.java", "mutastic/remove-negation", Inputs.NUMBERS),
          new Fixture("arithmetic/Bitwise.java", "mutastic/bitwise", Inputs.NUMBERS),
          new Fixture("arithmetic/KeptOperands.java", "mutastic/keep-operand", Inputs.NUMBERS),
          new Fixture("arithmetic/ConstantValues.java", "mutastic/constant-value", Inputs.NUMBERS),
          new Fixture(
              "arithmetic/OtherArithmetic.java", "mutastic/other-arithmetic", Inputs.NUMBERS),
          new Fixture("arithmetic/Checked.java", "library/unchecked-arithmetic", Inputs.NUMBERS),
          new Fixture("switches/Arms.java", "mutastic/remove-switch-arm", Inputs.NUMBERS),
          new Fixture("discards/Discards.java", "mutastic/remove-discarded-call", Inputs.NUMBERS),
          new Fixture("discards/Discards.java", "mutastic/remove-void-call", Inputs.NUMBERS),
          new Fixture("stores/Assignments.java", "mutastic/remove-assignment", Inputs.NUMBERS),
          new Fixture("discards/Leftovers.java", "mutastic/remove-discarded-call", Inputs.NUMBERS),
          new Fixture("discards/Leftovers.java", "mutastic/remove-void-call", Inputs.NUMBERS),
          new Fixture("discards/Leftovers.java", "mutastic/remove-assignment", Inputs.NUMBERS),
          new Fixture("returns/Returns.java", "mutastic/return-value", Inputs.NUMBERS),
          new Fixture("calls/Omitted.java", "mutastic/omit-call"),
          new Fixture("calls/Swapped.java", "mutastic/swap-arguments"),
          new Fixture("calls/Dropped.java", "mutastic/drop-vararg"),
          new Fixture("calls/LessArguments.java", "library/omit-argument"),
          new Fixture("library/Swaps.java", "library/swap-string"),
          new Fixture("library/Swaps.java", "library/swap-index-of"),
          new Fixture("library/Swaps.java", "library/swap-math"),
          new Fixture("rules/Endless.java", "stream/remove-stage"),
          new Fixture("rules/Optionals.java", "optional/swap-present"),
          new Fixture("rules/Optionals.java", "optional/empty"),
          new Fixture("rules/Optionals.java", "optional/remove-filter"),
          new Fixture("rules/Optionals.java", "optional/always-default"),
          new Fixture("rules/Optionals.java", "optional/no-default"),
          new Fixture("rules/Predicates.java", "predicate/swap-connector"),
          new Fixture("rules/Predicates.java", "predicate/remove-negate"),
          new Fixture("rules/Predicates.java", "predicate/remove-not"),
          new Fixture("rules/Predicates.java", "stream/remove-other-stage"),
          new Fixture("text/Strings.java", "mutastic/string-constant"),
          new Fixture("text/Regexes.java", "mutastic/regex-constant"),
          new Fixture("exceptions/Throws.java", "mutastic/remove-throw"),
          new Fixture("exceptions/Throws.java", "mutastic/drop-catch-type"));

  @Test
  void theLinesACardShowsAreItsClaims() throws Exception {
    var shown = new ArrayList<String>();
    var failures = new ArrayList<String>();
    for (var fixture : FIXTURES) {
      new ClaimFixture(fixture.file(), fixture.operator(), fixture.inputs())
          .shownChangesThatAreNotTheMutants(shown)
          .forEach(failure -> failures.add(fixture.operator() + " " + failure));
    }

    assertThat(failures).as("cards that show lines that aren't the mutant's").isEmpty();
    assertThat(shown).as("the cards whose lines were checked").hasSizeGreaterThan(100);
    assertThat(shown)
        .as("a removed part that still runs, shown as what's left")
        .anyMatch(card -> card.startsWith("anArgument ") && card.endsWith(": seen.addAndGet(b);"))
        .anyMatch(card -> card.startsWith("aReceiver ") && card.endsWith(": listIn(made);"))
        .anyMatch(
            card -> card.startsWith("aVoidCallsArgument ") && card.endsWith(": seen.getAndAdd(b);"))
        .anyMatch(
            card -> card.startsWith("afterAComment ") && card.endsWith(": seen.addAndGet(b);"))
        .anyMatch(card -> card.startsWith("inAConditional ") && card.endsWith(": "))
        .anyMatch(
            card ->
                card.startsWith("aBracketedValue ") && card.endsWith(": seen.incrementAndGet();"));
  }
}
