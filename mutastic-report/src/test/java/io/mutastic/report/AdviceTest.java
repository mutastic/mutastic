package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Operator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdviceTest {
  @Test
  void readsAnIfBackToItsSourceOperator() {
    var advice = Advice.of(mutant(BuiltIn.BOUNDARY, "IF_ICMPLT", "IF_ICMPLE", "(I)I"));

    assertThat(advice.before()).contains(">=");
    assertThat(advice.after()).contains(">");
    assertThat(advice.headline()).isEqualTo("no test checks the boundary of `>=` in `discount()`");
  }

  @Test
  void negatesComparisonsAndNullChecks() {
    assertThat(
            Advice.of(mutant(BuiltIn.NEGATE_CONDITIONAL, "IF_ICMPNE", "IF_ICMPEQ", "(I)I"))
                .change())
        .isEqualTo("`==` becomes `!=`");
    assertThat(
            Advice.of(mutant(BuiltIn.NEGATE_CONDITIONAL, "IFNULL", "IFNONNULL", "(I)I")).change())
        .isEqualTo("`!=` becomes `==`");
    assertThat(Advice.of(mutant(BuiltIn.NEGATE_CONDITIONAL, "IFEQ", "IFNE", "(I)I")).headline())
        .isEqualTo("`discount()` behaves the same with the condition reversed");
  }

  @Test
  void readsAJumpOnABooleanByItsOpcode() {
    // `if (inCredit(b) || ...)`: IFNE into the body, so never jumping is as if it were false.
    assertThat(removed("IFNE", "never jump").change()).isEqualTo("`inCredit(b)` is always false");
    assertThat(removed("IFNE", "always jump").change()).isEqualTo("`inCredit(b)` is always true");
    // `if (inCredit(b))`: IFEQ past the body, javac's usual way round.
    assertThat(removed("IFEQ", "never jump").change()).isEqualTo("`inCredit(b)` is always true");
    assertThat(removed("IFEQ", "never jump").headline())
        .isEqualTo("no test needs `inCredit(b)` to be false in `discount()`");
  }

  @Test
  void saysTheOtherValueATestNeeds() {
    // IFNE never jumping: as if `inCredit(b)` were always false, so a test needs it true.
    assertThat(removed("IFNE", "never jump").headline())
        .isEqualTo("no test needs `inCredit(b)` to be true in `discount()`");
    assertThat(removed("IFNE", "never jump").fix())
        .isEqualTo(
            "add a test where `inCredit(b)` is true, and assert what `discount()` does then");
  }

  /// `if (count != 0)` compiles to IFEQ past the body; `if (count == 0)` to IFNE.
  @Test
  void readsAComparisonWithZeroAsTheSourceWritesIt() {
    var notZero = with(BuiltIn.REMOVE_CONDITIONAL, "IFEQ", "never jump", "count", "0");
    assertThat(Advice.of(notZero).change()).isEqualTo("`count != 0` is always true");
    var zero = with(BuiltIn.REMOVE_CONDITIONAL, "IFNE", "always jump", "count", "0");
    assertThat(Advice.of(zero).change()).isEqualTo("`count == 0` is always false");
    var negated = with(BuiltIn.NEGATE_CONDITIONAL, "IFEQ", "IFNE", "count", "0");
    assertThat(Advice.of(negated).change()).isEqualTo("`!=` becomes `==`");
    assertThat(Advice.of(negated).fix())
        .isEqualTo("assert the result on both sides of `count != 0`");
  }

  @Test
  void saysTheInputsTheTestsPassed() {
    var seen = Map.of("total", List.of(5L, 20L));
    var removed = with(BuiltIn.REMOVE_CONDITIONAL, "IF_ICMPLT", "never jump", "total", "100");
    assertThat(Advice.of(removed, seen, false).notes())
        .containsExactly("inputs seen: total = 5, 20");
    assertThat(Advice.of(removed).notes()).isEmpty();
    // At a boundary, the inputs say how far from it the tests stay.
    var boundary = with(BuiltIn.BOUNDARY, "IF_ICMPLT", "IF_ICMPLE", "total", "100");
    assertThat(Advice.of(boundary, seen, false).notes())
        .containsExactly(
            "every test calls `discount()` away from the boundary",
            "inputs seen: 5, 20 (none is 100)");
  }

  @Test
  void namesTheArithmeticWhenItsOperandsAreKnown() {
    var known = Advice.of(with(BuiltIn.MATH, "IMUL", "IDIV", "price", "quantity"));
    assertThat(known.headline()).isEqualTo("tests pass even when `*` becomes `/` in `discount()`");
    assertThat(known.fix())
        .isEqualTo(
            "use inputs where `price * quantity` and `price / quantity` differ, and assert the"
                + " result");
    var unknown = Advice.of(mutant(BuiltIn.MATH, "IMUL", "IDIV", "(I)I"));
    assertThat(unknown.fix())
        .isEqualTo("use inputs where `*` and `/` differ, and assert the result");
  }

  @Test
  void namesALambdaAndAnAnonymousClassAsTheSourceHasThem() {
    var inLambda =
        new Mutant(
            "com/example/Pricing",
            "lambda$discount$0",
            "(I)Z",
            0,
            5,
            BuiltIn.NEGATE_CONDITIONAL,
            "IF_ICMPLT",
            "IF_ICMPGE",
            Optional.empty(),
            List.of(),
            List.of("total", "100"));
    assertThat(Advice.of(inLambda).headline())
        .isEqualTo("A lambda in `discount()` behaves the same with `total >= 100` reversed");
    var anonymous =
        new Mutant(
            "com/example/Pricing$1",
            "compare",
            "(II)I",
            0,
            5,
            BuiltIn.RETURN_DEFAULT,
            "IRETURN",
            "return default",
            Optional.empty());
    assertThat(Advice.of(anonymous).headline())
        .isEqualTo("no test checks what `compare()` in an anonymous class returns here");
  }

  private static Mutant with(
      Operator operator, String original, String replacement, String left, String right) {
    return new Mutant(
        "com/example/Pricing",
        "discount",
        "(I)I",
        0,
        5,
        operator,
        original,
        replacement,
        Optional.empty(),
        List.of(),
        List.of(left, right));
  }

  private static Advice removed(String jump, String replacement) {
    return Advice.of(
        new Mutant(
            "com/example/Pricing",
            "discount",
            "(I)I",
            0,
            5,
            BuiltIn.REMOVE_CONDITIONAL,
            jump,
            replacement,
            Optional.empty(),
            List.of(),
            List.of("inCredit(b)")));
  }

  @Test
  void returnsTheDefaultForTheReturnType() {
    assertThat(
            Advice.of(mutant(BuiltIn.RETURN_DEFAULT, "IRETURN", "return default", "(I)Z")).after())
        .contains("return false;");
    assertThat(
            Advice.of(mutant(BuiltIn.RETURN_DEFAULT, "LRETURN", "return default", "(I)J")).after())
        .contains("return 0L;");
    assertThat(
            Advice.of(
                    mutant(
                        BuiltIn.RETURN_DEFAULT, "ARETURN", "return default", "()Ljava/util/List;"))
                .change())
        .isEqualTo("returns `null` instead");
  }

  @Test
  void namesTheRemovedCall() {
    var advice =
        Advice.of(
            mutant(
                BuiltIn.REMOVE_VOID_CALL,
                "INVOKEVIRTUAL com/example/Audit.record(Ljava/lang/String;)V",
                "remove call",
                "(I)I"));

    assertThat(advice.headline())
        .isEqualTo("no test checks the effect of calling `record()` in `discount()`");
  }

  private static Mutant mutant(
      Operator operator, String original, String replacement, String descriptor) {
    return new Mutant(
        "com/example/Pricing",
        "discount",
        descriptor,
        0,
        5,
        operator,
        original,
        replacement,
        Optional.empty());
  }
}
