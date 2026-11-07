package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class KotlinNamesTest {
  @Test
  void aParameterIsAsAPersonWouldCallIt() {
    assertThat(KotlinNames.parameter("$this$doubled")).isEqualTo("receiver");
    assertThat(KotlinNames.parameter("<set-?>")).isEqualTo("value");
    assertThat(KotlinNames.parameter("total")).isEqualTo("total");
  }

  @Test
  void anOperandIsAsTheSourceWritesIt() {
    assertThat(KotlinNames.operands(List.of("x$iv", "10"))).containsExactly("x", "10");
    assertThat(KotlinNames.operands(List.of("x$iv$iv"))).containsExactly("x");
    assertThat(KotlinNames.operands(List.of("$this$doubled", "2"))).containsExactly("this", "2");
    assertThat(KotlinNames.operands(List.of("total", "100"))).containsExactly("total", "100");
  }

  /// Better to say less than to name a slot.
  @Test
  void anOperandWithNoNameTakesTheOthersWithIt() {
    assertThat(KotlinNames.operands(List.of("local3", "local4"))).isEmpty();
    assertThat(KotlinNames.operands(List.of("total", "local4"))).isEmpty();
    assertThat(KotlinNames.operands(List.of("$result", "1"))).isEmpty();
    assertThat(KotlinNames.operands(List.of("locals", "1"))).containsExactly("locals", "1");
  }
}
