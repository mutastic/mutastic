package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/// How a call is written, once it's known what it is: `KotlinShapesTest` has real kotlinc output
/// say what it is, and compiles the result.
class KotlinCallTest {
  private static KotlinCall call(KotlinCall.Kind kind, boolean property, boolean extension) {
    return new KotlinCall(kind, "f", property, extension);
  }

  @Test
  void aFunctionOnWhatItsMadeOn() {
    assertThat(call(KotlinCall.Kind.TOP_LEVEL, false, false).written("", List.of("1", "2L")))
        .isEqualTo("f(1, 2L)");
    assertThat(call(KotlinCall.Kind.ON_AN_INSTANCE, false, false).written("Box(4)", List.of("1")))
        .isEqualTo("Box(4).f(1)");
    assertThat(call(KotlinCall.Kind.ON_THE_CLASS, false, false).written("Box", List.of()))
        .isEqualTo("Box.f()");
  }

  @Test
  void aPropertyHasNoBrackets() {
    assertThat(call(KotlinCall.Kind.ON_AN_INSTANCE, true, false).written("Box(4)", List.of()))
        .isEqualTo("Box(4).f");
    assertThat(call(KotlinCall.Kind.TOP_LEVEL, true, false).written("", List.of())).isEqualTo("f");
  }

  @Test
  void anExtensionsFirstArgumentGoesBeforeTheDot() {
    assertThat(call(KotlinCall.Kind.TOP_LEVEL, false, true).written("", List.of("1", "2")))
        .isEqualTo("1.f(2)");
    assertThat(call(KotlinCall.Kind.TOP_LEVEL, false, true).written("", List.of("-1")))
        .isEqualTo("(-1).f()");
    assertThat(call(KotlinCall.Kind.TOP_LEVEL, true, true).written("", List.of("3")))
        .isEqualTo("3.f");
  }
}
