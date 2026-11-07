package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.ClassMutator;
import io.mutastic.api.Mutant;
import io.mutastic.api.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ExplanationTest {
  @TempDir Path project;

  private Explanation explanation;

  @BeforeEach
  void source() throws Exception {
    var file = project.resolve("src/main/java/com/example/Pricing.java");
    Files.createDirectories(file.getParent());
    Files.writeString(
        file,
        """
        package com.example;

        public class Pricing {
          public int discount(int total) {
            if (total >= 100) {
              audit(total);
              return total / 10;
            }
            return 0;
          }

          private void audit(int total) {}

          public int band(int total) {
            if (total < 0 || total > 500) {
              return 0;
            }
            return 1;
          }
        }
        """);
    explanation =
        new Explanation(
            new SourceView(project, List.of(project.resolve("src/main/java"))), Style.PLAIN);
  }

  @Test
  void aJumpWithKnownOperandsIsSaidInTheSourcesTerms() {
    var text =
        explanation.render(
            jump(List.of("total", "100")),
            Optional.of(new Explanation.LastRun(Status.CAUGHT, Optional.of("a.PricingTest#b"))));

    assertThat(text)
        .contains("IF_ICMPLT → IF_ICMPLE")
        .contains("javac compiles `total >= 100` to IF_ICMPLT")
        .contains("instruction site 1 of discount(I)I")
        .contains("caught by PricingTest");
  }

  @Test
  void aJumpWithoutOperandsIsSaidInGeneral() {
    var text =
        explanation.render(
            jump(List.of()), Optional.of(new Explanation.LastRun(Status.MISSED, Optional.empty())));

    assertThat(text)
        .contains("a jump past its body when the condition is false")
        .contains("missed: every covering test still passed");
  }

  @Test
  void aChangeToAClassAsAWholeIsntCountedAmongInstructions() {
    var mutant =
        new WholeClassStub(ClassMutator.Reach.EVERY_TEST)
            .mutant(ClassMutator.Member.ofField("code", "I"), 3);

    assertThat(explanation.render(mutant, Optional.empty()))
        .contains("a change to the class as a whole, on <field code>")
        .doesNotContain("instruction site");
  }

  @Test
  void aCallIsCountedAmongCalls() {
    var call =
        new Mutant(
            "com/example/Pricing",
            "discount",
            "(I)I",
            0,
            6,
            BuiltIn.REMOVE_VOID_CALL,
            "INVOKEVIRTUAL com/example/Pricing.audit(I)V",
            "remove call",
            Optional.empty());

    var text = explanation.render(call, Optional.empty());

    assertThat(text)
        .contains("call Pricing.audit()")
        .contains("call site 1 of discount(I)I")
        .contains("calls are counted in bytecode order")
        .contains("none yet: a full run records one")
        .doesNotContain("javac compiles");
  }

  @Test
  void eachLastRunIsSaid() {
    var math =
        new Mutant(
            "com/example/Pricing",
            "discount",
            "(I)I",
            1,
            7,
            BuiltIn.ARITHMETIC_REPLACEMENT,
            "IDIV",
            "IMUL",
            Optional.empty());

    assertThat(lastRun(math, Status.TIMED_OUT)).contains("caught: its tests ran out of time");
    assertThat(lastRun(math, Status.NOT_COVERED)).contains("not covered: no test reached its line");
    assertThat(lastRun(math, Status.SKIPPED)).contains("last run  skipped");
    assertThat(lastRun(math, Status.CAUGHT)).contains("caught\n").doesNotContain("caught by");
    assertThat(lastRun(math, Status.SKIPPED)).contains(", and the operator");
  }

  /// The first test of an `||` jumps into the body when it holds: it reads as the source does.
  @Test
  void theFirstTestOfAnOrReadsAsWritten() {
    var first = condition(BuiltIn.BOUNDARY, "IFLT", "IFLE", 15, "total", "0");
    assertThat(explanation.render(first, Optional.empty()))
        .contains("here the jump is taken when the condition holds")
        .doesNotContain("javac compiles");

    var second =
        condition(BuiltIn.REMOVE_CONDITIONAL, "IF_ICMPLE", "never jump", 15, "total", "500");
    assertThat(explanation.render(second, Optional.empty()))
        .contains("a jump past its body when the condition is false");
    var negated = condition(BuiltIn.OTHER_COMPARISON, "IF_ICMPLE", "IF_ICMPGT", 15, "total", "500");
    assertThat(explanation.render(negated, Optional.empty()))
        .contains("javac compiles `total > 500` to IF_ICMPLE");
  }

  /// A comparison with zero has no int-comparing symbol to put the jump in the source's terms.
  @Test
  void aComparisonWithZeroIsSaidInGeneral() {
    var zero = condition(BuiltIn.OTHER_COMPARISON, "IFEQ", "IFNE", 5, "total", "0");
    assertThat(explanation.render(zero, Optional.empty()))
        .contains("a jump past its body when the condition is false")
        .doesNotContain("javac compiles `");
  }

  private static Mutant condition(
      BuiltIn operator, String original, String replacement, int line, String... operands) {
    return new Mutant(
        "com/example/Pricing",
        line == 15 ? "band" : "discount",
        "(I)I",
        0,
        line,
        operator,
        original,
        replacement,
        Optional.empty(),
        List.of(),
        List.of(operands));
  }

  private String lastRun(Mutant mutant, Status status) {
    return explanation.render(
        mutant, Optional.of(new Explanation.LastRun(status, Optional.empty())));
  }

  private static Mutant jump(List<String> operands) {
    return new Mutant(
        "com/example/Pricing",
        "discount",
        "(I)I",
        0,
        5,
        BuiltIn.BOUNDARY,
        "IF_ICMPLT",
        "IF_ICMPLE",
        Optional.empty(),
        List.of(),
        operands);
  }
}
