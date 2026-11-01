package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.Mutant;
import io.mutastic.engine.Operator;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class AdviceTest {
  @Test
  void readsAnIfBackToItsSourceOperator() {
    var advice = Advice.of(mutant(Operator.BOUNDARY, "IF_ICMPLT", "IF_ICMPLE", "(I)I"));

    assertThat(advice.before()).contains(">=");
    assertThat(advice.after()).contains(">");
    assertThat(advice.headline()).isEqualTo("no test checks the edge of `>=` in `discount()`");
  }

  @Test
  void negatesComparisonsAndNullChecks() {
    assertThat(Advice.of(mutant(Operator.NEGATE_CONDITIONAL, "IF_ICMPNE", "IF_ICMPEQ", "(I)I")).change())
        .isEqualTo("`==` becomes `!=`");
    assertThat(Advice.of(mutant(Operator.NEGATE_CONDITIONAL, "IFNULL", "IFNONNULL", "(I)I")).change())
        .isEqualTo("`!=` becomes `==`");
    assertThat(Advice.of(mutant(Operator.NEGATE_CONDITIONAL, "IFEQ", "IFNE", "(I)I")).headline())
        .isEqualTo("no test checks both outcomes of the condition in `discount()`");
  }

  @Test
  void returnsTheDefaultForTheReturnType() {
    assertThat(Advice.of(mutant(Operator.RETURN_DEFAULT, "IRETURN", "return default", "(I)Z")).after()).contains("return false;");
    assertThat(Advice.of(mutant(Operator.RETURN_DEFAULT, "LRETURN", "return default", "(I)J")).after()).contains("return 0L;");
    assertThat(Advice.of(mutant(Operator.RETURN_DEFAULT, "ARETURN", "return default", "()Ljava/util/List;")).change())
        .isEqualTo("returns `null` instead");
  }

  @Test
  void namesTheRemovedCall() {
    var advice = Advice.of(mutant(Operator.REMOVE_VOID_CALL,
        "INVOKEVIRTUAL com/example/Audit.record(Ljava/lang/String;)V", "remove call", "(I)I"));

    assertThat(advice.headline()).isEqualTo("no test checks the effect of calling `record()` in `discount()`");
  }

  private static Mutant mutant(Operator operator, String original, String replacement, String descriptor) {
    return new Mutant("com/example/Pricing", "discount", descriptor, 0, 5, operator, original, replacement, Optional.empty());
  }
}
