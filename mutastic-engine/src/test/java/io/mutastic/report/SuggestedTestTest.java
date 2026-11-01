package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.Mutant;
import io.mutastic.engine.MutantResult;
import io.mutastic.engine.Operator;
import io.mutastic.engine.Status;
import io.mutastic.engine.Suggestion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SuggestedTestTest {
  @TempDir
  Path project;

  @Test
  void writesJUnitAssertionsByDefault() {
    var test = SuggestedTest.of(missed(suggestion("10", "0")), sources()).orElseThrow();

    assertThat(test.testClass()).isEqualTo("PricingTest");
    assertThat(test.lines()).containsExactly(
        "@Test",
        "void discountWithTotal100() {",
        "  assertEquals(10, new Pricing().discount(100)); // today 10 · with this mutant 0",
        "}");
  }

  @Test
  void writesAssertJWhereTheTestClassUsesIt() throws Exception {
    testSource("import static org.assertj.core.api.Assertions.assertThat;\nclass PricingTest {}\n");

    var test = SuggestedTest.of(missed(suggestion("10", "0")), sources()).orElseThrow();

    assertThat(test.lines().get(2)).isEqualTo("  assertThat(new Pricing().discount(100)).isEqualTo(10); // today 10 · with this mutant 0");
  }

  @Test
  void booleansNullsAndExceptions() {
    assertThat(SuggestedTest.assertion(suggestion("true", "false"), false)).isEqualTo("assertTrue(new Pricing().discount(100));");
    assertThat(SuggestedTest.assertion(suggestion("false", "true"), true)).isEqualTo("assertThat(new Pricing().discount(100)).isFalse();");
    assertThat(SuggestedTest.assertion(suggestion("null", "\"x\""), false)).isEqualTo("assertNull(new Pricing().discount(100));");
    assertThat(SuggestedTest.assertion(suggestion("throws java.lang.IllegalStateException", "0"), false))
        .isEqualTo("assertThrows(java.lang.IllegalStateException.class, () -> new Pricing().discount(100));");
    assertThat(SuggestedTest.assertion(suggestion("throws java.lang.IllegalStateException", "0"), true))
        .isEqualTo("assertThatThrownBy(() -> new Pricing().discount(100)).isInstanceOf(java.lang.IllegalStateException.class);");
  }

  @Test
  void namesSayTheInputsAndNeverClash() throws Exception {
    testSource("class PricingTest {\n  @Test void discountWithTotal100() {}\n  @Test void discountWithTotal100 () {}\n}\n");
    var negative = new Suggestion("Fees.fee(-1, 3)", List.of("amount", "count"), List.of(-1L, 3L), "0", "1");

    assertThat(SuggestedTest.name("fee", negative)).isEqualTo("feeWithAmountMinus1AndCount3");
    assertThat(SuggestedTest.of(missed(suggestion("10", "0")), sources()).orElseThrow().name()).isEqualTo("discountWithTotal1002");
    assertThat(SuggestedTest.name("size", new Suggestion("new Stock().size()", List.of(), List.of(), "0", "1")))
        .isEqualTo("sizeResult");
    // Receivers made with arguments: those are in the name too, so two calls never share one.
    var ten = new Suggestion("new Window(5, 10L).openOn(5)", List.of("day"), List.of(5L), "Window", List.of(5L, 10L), "true",
        "false");
    var five = new Suggestion("new Window(5, 5L).openOn(5)", List.of("day"), List.of(5L), "Window", List.of(5L, 5L), "false",
        "true");
    assertThat(SuggestedTest.name("openOn", ten)).isEqualTo("openOnWithDay5ForWindow5And10");
    assertThat(SuggestedTest.name("openOn", five)).isEqualTo("openOnWithDay5ForWindow5And5");
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
    return new Suggestion("new Pricing().discount(100)", List.of("total"), List.of(100L), today, mutated);
  }

  private static MutantResult missed(Suggestion suggestion) {
    var mutant = new Mutant("com/example/Pricing", "discount", "(I)I", 0, 5, Operator.BOUNDARY, "IF_ICMPLT", "IF_ICMPLE",
        Optional.empty());
    return new MutantResult(mutant, Status.MISSED, List.of("com.example.PricingTest#bigOrders"), Optional.empty(),
        Optional.empty(), Optional.empty(), 1, Optional.empty(), Map.of(), Optional.of(suggestion), Duration.ZERO);
  }
}
