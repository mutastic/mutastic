package io.mutastic.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class TestSelectionTest {
  @Test
  void noPatternsRunsEverything() {
    assertThat(TestSelection.ALL.runs("com.example.PricingIT")).isTrue();
  }

  /// As Ant writes them: `**/` also matches no directory at all.
  @Test
  void globsMatchClassFilesInAnyPackageIncludingNone() {
    var selection =
        new TestSelection(List.of("**/*Test.class"), List.of("**/*IT.class"), List.of(), List.of());

    assertThat(selection.runs("com.example.PricingTest")).isTrue();
    assertThat(selection.runs("PricingTest")).isTrue();
    assertThat(selection.runs("com.example.PricingIT")).isFalse();
    assertThat(selection.runs("com.example.Helper")).isFalse();
  }

  @Test
  void excludesWinOverIncludes() {
    var selection =
        new TestSelection(
            List.of("**/*Test.class"), List.of("com/example/slow/**"), List.of(), List.of());

    assertThat(selection.runs("com.example.slow.BigTest")).isFalse();
    assertThat(selection.runs("com.example.fast.SmallTest")).isTrue();
  }

  @Test
  void regularExpressionsToo() {
    var selection =
        new TestSelection(
            List.of("regex:.*(Cat|Dog)Test\\.class"), List.of(), List.of(), List.of());

    assertThat(selection.runs("com.example.CatTest")).isTrue();
    assertThat(selection.runs("com.example.CowTest")).isFalse();
  }

  @Test
  void anyIncludeIsEnough() {
    var selection =
        new TestSelection(
            List.of("**/*Test.class", "**/*IT.class"), List.of(), List.of(), List.of());

    assertThat(selection.runs("com.example.PricingTest")).isTrue();
    assertThat(selection.runs("com.example.PricingIT")).isTrue();
    assertThat(selection.runs("com.example.Helper")).isFalse();
  }

  @Test
  void leavingOutNamesAClassWithOrWithoutItsPackage() {
    var selection = TestSelection.ALL.leavingOut(List.of("SlowTest", "com.example.BigTest"));

    assertThat(selection.runs("com.example.deep.SlowTest")).isFalse();
    assertThat(selection.runs("com.example.BigTest")).isFalse();
    assertThat(selection.runs("com.other.BigTest")).isTrue();
  }
}
