package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Operator;
import io.mutastic.api.Policy;
import io.mutastic.engine.fixture.Orders;
import io.mutastic.engine.fixture.Pricing;
import java.lang.classfile.ClassFile;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// The operators added after the first five: remove conditional, arithmetic replacement and the
/// stream rules, and how a run chooses among them.
class NewOperatorsTest {
  private final Mutations defaults = new Mutations(ClassFile.of());
  private final Mutations stronger =
      new Mutations(ClassFile.of(), Policy.NONE.withOperators(List.of("stronger"), List.of()));

  @Test
  void removeConditionalStopsTheConditionDeciding() throws Exception {
    var always =
        mutant(defaults, Pricing.class, "discount", BuiltIn.REMOVE_CONDITIONAL, "always jump");
    var never =
        mutant(defaults, Pricing.class, "discount", BuiltIn.REMOVE_CONDITIONAL, "never jump");

    // javac jumps past the body when the condition is false: always jumping, it never runs.
    assertThat(call(always, "discount", 250)).isEqualTo(0); // was 25
    // Never jumping, it always does.
    assertThat(call(never, "discount", 50)).isEqualTo(5); // was 0
  }

  @Test
  void removeConditionalCoversNullAndReferenceChecks() throws Exception {
    var found = defaults.find(MutationsTest.model(Pricing.class));

    assertThat(found)
        .filteredOn(mutant -> mutant.methodName().equals("isFree"))
        .filteredOn(mutant -> mutant.operator() == BuiltIn.REMOVE_CONDITIONAL)
        .extracting(Mutant::original)
        .contains("IFNULL");
  }

  @Test
  void arithmeticReplacementIsStrongerOnly() throws Exception {
    assertThat(defaults.find(MutationsTest.model(Pricing.class)))
        .noneMatch(mutant -> mutant.operator() == BuiltIn.ARITHMETIC_REPLACEMENT);

    var times = mutant(stronger, Pricing.class, "total", BuiltIn.ARITHMETIC_REPLACEMENT, "LMUL");
    var pricing = load(Pricing.class, times);
    var total =
        pricing.getClass().getMethod("total", long.class, int.class).invoke(pricing, 10L, 2);

    assertThat(total).isEqualTo(100L); // price * quantity * 5, was 25
  }

  @Test
  void theFirstFiveKeepTheirIdsAndTheRestAddTheirOperator() {
    var boundary = site(BuiltIn.BOUNDARY, "IF_ICMPLE");
    var removed = site(BuiltIn.REMOVE_CONDITIONAL, "IF_ICMPLE");

    // The id the pricing sample's boundary mutant has always had.
    assertThat(
            new Mutant(
                    "com/example/pricing/Pricing",
                    "discount",
                    "(I)I",
                    0,
                    17,
                    BuiltIn.BOUNDARY,
                    "IF_ICMPLT",
                    "IF_ICMPLE",
                    Optional.empty())
                .id())
        .isEqualTo("m855c8e3c");
    assertThat(removed.id()).isNotEqualTo(boundary.id());
  }

  @Test
  void aStreamStageIsRemovedAndTheStreamGoesOn() throws Exception {
    var filter = mutant(defaults, Orders.class, "large", "stream/remove-stage", "remove");

    assertThat(filter.replacement()).isEqualTo("remove");
    var orders = load(Orders.class, filter);
    assertThat(orders.getClass().getMethod("large", List.class).invoke(orders, List.of(50, 150)))
        .isEqualTo(2L); // was 1
  }

  @Test
  void sortingBeforeAnOrderFreeResultIsEquivalent() throws Exception {
    var sorted = mutant(defaults, Orders.class, "distinctCount", "stream/remove-stage", "remove");
    var sortedThenLimited =
        mutant(defaults, Orders.class, "firstTwo", "stream/remove-stage", "remove");

    assertThat(sorted.skipped())
        .hasValueSatisfying(reason -> assertThat(reason).startsWith("equivalent: `.sorted()`"));
    assertThat(sortedThenLimited.skipped()).isEmpty();
  }

  @Test
  void anyMatchBecomesAllMatchAndNoneMatch() throws Exception {
    var found = defaults.find(MutationsTest.model(Orders.class));

    assertThat(found)
        .filteredOn(mutant -> mutant.operator().id().equals("stream/swap-match"))
        .extracting(Mutant::replacement)
        .containsExactly("allMatch", "noneMatch");
    var all = mutant(defaults, Orders.class, "anyLarge", "stream/swap-match", "allMatch");
    var orders = load(Orders.class, all);
    assertThat(orders.getClass().getMethod("anyLarge", List.class).invoke(orders, List.of(50, 150)))
        .isEqualTo(false); // was true
  }

  @Test
  void everyNewMutantVerifies() throws Exception {
    // Tail has a local with a type annotation, whose offsets a mutant that changes the code's
    // length mustn't leave behind.
    for (Class<?> type :
        List.of(Pricing.class, Orders.class, io.mutastic.engine.fixture.Tail.class)) {
      var model = MutationsTest.model(type);
      for (Mutant mutant : stronger.find(model)) {
        assertThat(stronger.verify(stronger.apply(model, mutant))).as(mutant.toString()).isEmpty();
      }
    }
  }

  @Test
  void aChoiceAddsAndLeavesOut() {
    assertThat(ids(List.of()))
        .contains("mutastic/remove-conditional", "stream/remove-stage")
        .doesNotContain("mutastic/arithmetic-replacement");
    assertThat(ids(List.of("stronger"))).contains("mutastic/arithmetic-replacement");
    assertThat(ids(List.of("-stream")))
        .contains("mutastic/boundary")
        .doesNotContain("stream/swap-match");
    assertThat(ids(List.of("mutastic/boundary"))).containsExactly("mutastic/boundary");
    assertThatThrownBy(() -> ids(List.of("streams")))
        .hasMessageStartingWith(
            "no operator or set is called streams; there are defaults, stronger,");
  }

  private static List<String> ids(List<String> chosen) {
    return OperatorSet.active(Policy.NONE.withOperators(chosen, List.of())).stream()
        .map(Operator::id)
        .toList();
  }

  private static Mutant site(Operator operator, String replacement) {
    return new Mutant(
        "a/B", "m", "()V", 0, 1, operator, "IF_ICMPLT", replacement, Optional.empty());
  }

  private static Mutant mutant(
      Mutations mutations, Class<?> type, String method, Operator operator, String replacement)
      throws Exception {
    return mutant(mutations, type, method, operator.id(), replacement);
  }

  private static Mutant mutant(
      Mutations mutations, Class<?> type, String method, String operator, String replacement)
      throws Exception {
    return mutations.find(MutationsTest.model(type)).stream()
        .filter(mutant -> mutant.methodName().equals(method))
        .filter(mutant -> mutant.operator().id().equals(operator))
        .filter(mutant -> mutant.replacement().equals(replacement))
        .findFirst()
        .orElseThrow();
  }

  private Object call(Mutant mutant, String method, int argument) throws Exception {
    var pricing = load(Pricing.class, mutant);
    return pricing.getClass().getMethod(method, int.class).invoke(pricing, argument);
  }

  private Object load(Class<?> type, Mutant mutant) throws Exception {
    var bytes = stronger.apply(MutationsTest.model(type), mutant);
    return new MutationsTest.SingleClassLoader(type.getName(), bytes)
        .loadClass(type.getName())
        .getConstructor()
        .newInstance();
  }
}
