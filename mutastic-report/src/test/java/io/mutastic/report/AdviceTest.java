package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.Operator;
import java.util.List;
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
