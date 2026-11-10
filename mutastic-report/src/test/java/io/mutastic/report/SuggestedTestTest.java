package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Status;
import io.mutastic.api.Suggestion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuggestedTestTest {
  @TempDir Path project;

  @Test
  void writesJUnitAssertionsByDefault() {
    var test = SuggestedTest.of(missed(suggestion("10", "0")), sources()).orElseThrow();

    assertThat(test.testClass()).isEqualTo("PricingTest");
    assertThat(test.lines())
        .containsExactly(
            "@Test",
            "void discountWithTotal100() {",
            "  assertEquals(10, new Pricing().discount(100)); // today 10 · with this mutant 0",
            "}");
  }

  @Test
  void writesAssertJWhereTheTestClassUsesIt() throws Exception {
    testSource("import static org.assertj.core.api.Assertions.assertThat;\nclass PricingTest {}\n");

    var test = SuggestedTest.of(missed(suggestion("10", "0")), sources()).orElseThrow();

    assertThat(test.lines().get(2))
        .isEqualTo(
            "  assertThat(new Pricing().discount(100)).isEqualTo(10); // today 10 · with this mutant 0");
  }

  @Test
  void booleansNullsAndExceptions() {
    assertThat(SuggestedTest.assertion(suggestion("true", "false"), SuggestedTest.Style.JUNIT))
        .isEqualTo("assertTrue(new Pricing().discount(100));");
    assertThat(SuggestedTest.assertion(suggestion("false", "true"), SuggestedTest.Style.ASSERTJ))
        .isEqualTo("assertThat(new Pricing().discount(100)).isFalse();");
    assertThat(SuggestedTest.assertion(suggestion("null", "\"x\""), SuggestedTest.Style.JUNIT))
        .isEqualTo("assertNull(new Pricing().discount(100));");
    assertThat(
            SuggestedTest.assertion(
                suggestion("throws java.lang.IllegalStateException", "0"),
                SuggestedTest.Style.JUNIT))
        .isEqualTo(
            "assertThrows(java.lang.IllegalStateException.class, () -> new Pricing().discount(100));");
    assertThat(
            SuggestedTest.assertion(
                suggestion("throws java.lang.IllegalStateException", "0"),
                SuggestedTest.Style.ASSERTJ))
        .isEqualTo(
            "assertThatThrownBy(() -> new Pricing().discount(100)).isInstanceOf(java.lang.IllegalStateException.class);");
  }

  /// TestNG's `assertEquals` takes the actual value first, and its test methods are public.
  @Test
  void testNgTestsAreWrittenTheTestNgWay() {
    assertThat(SuggestedTest.Style.of("import static org.testng.Assert.assertEquals;\n"))
        .isEqualTo(SuggestedTest.Style.TESTNG);
    assertThat(SuggestedTest.assertion(suggestion("10", "0"), SuggestedTest.Style.TESTNG))
        .isEqualTo("assertEquals(new Pricing().discount(100), 10);");
  }

  @Test
  void namesSayTheInputsAndNeverClash() throws Exception {
    testSource(
        "class PricingTest {\n  @Test void discountWithTotal100() {}\n  @Test void discountWithTotal100 () {}\n}\n");
    var negative =
        new Suggestion("Fees.fee(-1, 3)", List.of("amount", "count"), List.of(-1L, 3L), "0", "1");

    assertThat(SuggestedTest.name("fee", negative)).isEqualTo("feeWithAmountMinus1AndCount3");
    assertThat(SuggestedTest.of(missed(suggestion("10", "0")), sources()).orElseThrow().name())
        .isEqualTo("discountWithTotal1002");
    assertThat(
            SuggestedTest.name(
                "size", new Suggestion("new Stock().size()", List.of(), List.of(), "0", "1")))
        .isEqualTo("sizeResult");
    // Receivers made with arguments: those are in the name too, so two calls never share one.
    var ten =
        new Suggestion(
            "new Window(5, 10L).openOn(5)",
            List.of("day"),
            List.of(5L),
            List.of(new Suggestion.Instance("Window", List.of(5L, 10L))),
            "true",
            "false");
    var five =
        new Suggestion(
            "new Window(5, 5L).openOn(5)",
            List.of("day"),
            List.of(5L),
            List.of(new Suggestion.Instance("Window", List.of(5L, 5L))),
            "false",
            "true");
    assertThat(SuggestedTest.name("openOn", ten)).isEqualTo("openOnWithDay5ForWindow5And10");
    assertThat(SuggestedTest.name("openOn", five)).isEqualTo("openOnWithDay5ForWindow5And5");
    // An inner class: each instance is named with its own arguments, innermost first.
    assertThat(SuggestedTest.name("heavy", inner(List.of(10L), List.of(30L))))
        .isEqualTo("heavyResultForParcel30InShipping10");
    assertThat(SuggestedTest.name("heavy", inner(List.of(10L), List.of())))
        .isEqualTo("heavyResultForParcelInShipping10");
    assertThat(SuggestedTest.name("heavy", inner(List.of(), List.of(30L))))
        .isEqualTo("heavyResultForParcel30");
  }

  @Test
  void aValueTheSourceNamesNamesTheTestAndIsSaidInIt() throws Exception {
    testSource(
        "import static org.junit.jupiter.api.Assertions.assertEquals;\nclass PricingTest {}\n");
    var constant =
        new Suggestion(
            "new Pricing().discount(Pricing.FREE_FROM)",
            List.of("total"),
            List.of(100L),
            List.of(),
            "10",
            "0",
            Optional.of(new Suggestion.Named("FREE_FROM", "FREE_FROM = 100", Optional.empty())));
    var field =
        new Suggestion(
            "new Delivery(freeFrom).isFree(freeFrom)",
            List.of("total"),
            List.of(20L),
            List.of(new Suggestion.Instance("Delivery", List.of(20L), List.of("freeFrom"))),
            "true",
            "false",
            Optional.of(new Suggestion.Named("freeFrom", "", Optional.of("int freeFrom = 20;"))));

    assertThat(SuggestedTest.of(missed(constant), sources()).orElseThrow().lines())
        .containsExactly(
            "@Test",
            "void discountAtFreeFrom() {",
            "  assertEquals(10, new Pricing().discount(Pricing.FREE_FROM)); // FREE_FROM = 100 ·"
                + " today 10 · with this mutant 0",
            "}");
    assertThat(SuggestedTest.of(missed(field), sources()).orElseThrow().lines())
        .containsExactly(
            "@Test",
            "void discountAtFreeFrom() {",
            "  int freeFrom = 20;",
            "  assertTrue(new Delivery(freeFrom).isFree(freeFrom)); // today true · with this"
                + " mutant false",
            "}");
    // Without a value the source names, a constructor's arguments are named by its parameters.
    var plain =
        new Suggestion(
            "new Delivery(50).isFree(0)",
            List.of("total"),
            List.of(0L),
            List.of(new Suggestion.Instance("Delivery", List.of(50L), List.of("freeFrom"))),
            "false",
            "true");
    assertThat(SuggestedTest.name("isFree", plain)).isEqualTo("isFreeWithTotal0ForFreeFrom50");
    assertThat(SuggestedTest.pascal("FREE_FROM")).isEqualTo("FreeFrom");
    assertThat(SuggestedTest.pascal("freeFrom")).isEqualTo("FreeFrom");
  }

  /// `heavy()` on an inner class `Parcel`, made in a `Shipping`.
  private static Suggestion inner(List<Long> shipping, List<Long> parcel) {
    return new Suggestion(
        "shipping.new Parcel().heavy()",
        List.of(),
        List.of(),
        List.of(
            new Suggestion.Instance("Shipping", shipping),
            new Suggestion.Instance("Parcel", parcel)),
        "true",
        "false");
  }

  private SourceView sources() {
    return new SourceView(project, List.of(), List.of(project.resolve("src/test/java")));
  }

  private void testSource(String text) throws Exception {
    var file = project.resolve("src/test/java/com/example/PricingTest.java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }

  private static Suggestion suggestion(String today, String mutated) {
    return new Suggestion(
        "new Pricing().discount(100)", List.of("total"), List.of(100L), today, mutated);
  }

  private static MutantResult missed(Suggestion suggestion) {
    var mutant =
        new Mutant(
            "com/example/Pricing",
            "discount",
            "(I)I",
            0,
            5,
            BuiltIn.BOUNDARY,
            "IF_ICMPLT",
            "IF_ICMPLE",
            Optional.empty());
    return new MutantResult(
        mutant,
        Status.MISSED,
        List.of("com.example.PricingTest#bigOrders"),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        1,
        Optional.empty(),
        Map.of(),
        Optional.of(suggestion),
        Duration.ZERO);
  }
}
