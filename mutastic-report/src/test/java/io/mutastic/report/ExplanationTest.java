package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
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
