package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.api.Policy;
import io.mutastic.report.SourceView;
import java.lang.classfile.ClassFile;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/// Each card the extended set's rules give `Optional`, `Predicate` and the other stream stages says
/// what its mutant does: see [ClaimFixture].
class ModernApiTruthTest {
  private static final String OPTIONALS = "rules/Optionals.java";
  private static final String PREDICATES = "rules/Predicates.java";

  /// A rule's cards, one mutant at a time, compiled once.
  private static final List<String> OPTIONAL_RULES =
      List.of(
          "optional/swap-present",
          "optional/empty",
          "optional/remove-filter",
          "optional/always-default",
          "optional/no-default");

  private static final List<String> PREDICATE_RULES =
      List.of(
          "predicate/swap-connector",
          "predicate/remove-negate",
          "predicate/remove-not",
          "stream/remove-other-stage");

  private static SourceView sources;

  @BeforeAll
  static void readTheSources() {
    sources = new SourceView(ClaimFixture.ROOT, List.of(ClaimFixture.ROOT));
  }

  @Test
  void anOptionalRuleIsWhatItsClaimSays() throws Exception {
    for (String rule : OPTIONAL_RULES) {
      var fixture = new ClaimFixture(OPTIONALS, rule);
      assertThat(fixture.running()).as(rule).isNotEmpty();
      assertThat(fixture.claimsThatLie()).as(rule).isEmpty();
    }
  }

  @Test
  void aPredicateOrStageRuleIsWhatItsClaimSays() throws Exception {
    for (String rule : PREDICATE_RULES) {
      var fixture = new ClaimFixture(PREDICATES, rule);
      assertThat(fixture.running()).as(rule).isNotEmpty();
      assertThat(fixture.claimsThatLie()).as(rule).isEmpty();
    }
  }

  @Test
  void aStageThatCanChangeWhatTheStreamHoldsIsLeftAlone() throws Exception {
    var fixture = new ClaimFixture(PREDICATES, "stream/remove-other-stage");
    assertThat(fixture.changesIn("mappedObjects")).isEmpty();
    assertThat(fixture.changesIn("peekedObjects")).containsExactly("remove");
    assertThat(fixture.changesIn("mapped")).containsExactly("remove");
  }

  @Test
  void removingIfPresentIsTheVoidCallsChangeAndNoOtherRules() throws Exception {
    var voidCall = new ClaimFixture(OPTIONALS, "mutastic/remove-void-call");
    assertThat(voidCall.changesIn("aVoidCall")).containsExactly("remove call");
    for (String rule : OPTIONAL_RULES) {
      assertThat(new ClaimFixture(OPTIONALS, rule).changesIn("aVoidCall")).as(rule).isEmpty();
    }
  }

  @Test
  void anEmptyOptionalThrowsWithTheDefaultGone() throws Exception {
    var fixture = new ClaimFixture(OPTIONALS, "optional/no-default");
    var orElse =
        fixture.running().stream()
            .filter(mutant -> mutant.methodName().equals("orElse"))
            .findFirst()
            .orElseThrow();
    assertThat(fixture.outcomeOfOriginal("orElse", "b", "a")).isEqualTo("none");
    assertThat(fixture.outcomeOf(orElse, "b", "a")).isEqualTo("threw NoSuchElementException");
    assertThat(fixture.outcomeOf(orElse, "a", "a")).isEqualTo("a");
  }

  @Test
  void anOptionalsCardNamesTheTestItNeeds() throws Exception {
    var swap = onlyIn("optional/swap-present", OPTIONALS, "present");
    assertThat(sources.advice(swap).headline())
        .isEqualTo("no test tells `isPresent` from `isEmpty` in present()");
    assertThat(sources.advice(onlyIn("optional/empty", OPTIONALS, "ofAValue")).headline())
        .isEqualTo("no test checks the value in the `Optional` made by `of(…)` in ofAValue()");
    assertThat(sources.advice(onlyIn("optional/remove-filter", OPTIONALS, "filtered")).fix())
        .contains("a value the filter rejects");
    assertThat(sources.advice(onlyIn("optional/always-default", OPTIONALS, "orElse")).fix())
        .contains("has a value other than the default");
    assertThat(sources.advice(onlyIn("optional/no-default", OPTIONALS, "orElseGet")).headline())
        .isEqualTo("no test has an empty `Optional` reach `.orElseGet(…)` in orElseGet()");
  }

  @Test
  void aPredicatesCardNamesTheTestItNeeds() throws Exception {
    var swap = onlyIn("predicate/swap-connector", PREDICATES, "and");
    assertThat(sources.advice(swap).headline())
        .isEqualTo("no test tells `and` from `or` between two predicates in and()");
    assertThat(sources.advice(onlyIn("predicate/remove-not", PREDICATES, "not")).fix())
        .contains("a value the predicate accepts and one it rejects");
  }

  @Test
  void isEmptyIsntCalledFromAClassOlderThanItsMethod() throws Exception {
    assertThat(swappedInARelease("8").skipped())
        .hasValueSatisfying(
            why -> assertThat(why).startsWith("unviable: `isPresent`").contains("Java 11"));
    assertThat(swappedInARelease("11").skipped()).isEmpty();
  }

  /// `isPresent` in a class compiled for `release`: what the operator makes of it.
  private static Mutant swappedInARelease(String release) throws Exception {
    var source =
        new Javac.Source(
            "legacy.Present",
            """
            package legacy;

            class Present {
              boolean there(java.util.Optional<String> value) {
                return value.isPresent();
              }
            }
            """);
    var bytes =
        Javac.compile(List.of(source), List.of(), "--release", release).get("legacy.Present");
    var mutations =
        new Mutations(
            ClassFile.of(), Policy.NONE.withOperators(List.of("optional/swap-present"), List.of()));
    return mutations.find(ClassFile.of().parse(Objects.requireNonNull(bytes))).getFirst();
  }

  private static Mutant onlyIn(String rule, String file, String method) throws Exception {
    var found =
        new ClaimFixture(file, rule)
            .running().stream().filter(mutant -> mutant.methodName().equals(method)).toList();
    assertThat(found).as(rule + " in " + method).isNotEmpty();
    return found.getFirst();
  }
}
