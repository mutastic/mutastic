package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.engine.Mutant;
import io.mutastic.engine.MutantResult;
import io.mutastic.engine.Operator;
import io.mutastic.engine.Report;
import io.mutastic.engine.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HumanTextTest {
  @TempDir
  Path project;

  private SourceView sources;

  @BeforeEach
  void source() throws Exception {
    var file = project.resolve("src/main/java/com/example/Pricing.java");
    Files.createDirectories(file.getParent());
    Files.writeString(file, """
        package com.example;

        public class Pricing {
          public int discount(int total) {
            if (total >= 100) {
              return total / 10;
            }
            return 0;
          }
        }
        """);
    sources = new SourceView(project, List.of(project.resolve("src/main/java")));
  }

  @Test
  void aCardMarksTheOperatorInItsLine() {
    var card = text(false).card(missed(boundary()));

    assertThat(card).isEqualTo("""
        ✗ missed[boundary] m855c8e3c no test checks the edge of `>=` in `discount()`
          --> src/main/java/com/example/Pricing.java:5
          |
        4 |   public int discount(int total) {
        5 |     if (total >= 100) {
          |               ^^ `>=` becomes `>` and its covering test still passes
        6 |       return total / 10;
          |
          = mutant  - if (total >= 100) {
                    + if (total > 100) {
          = note: covered by PricingTest#bigOrders
          = help: add a test with the values where `>=` and `>` disagree (both sides equal), and assert the result
          = reproduce: mutastic run --only=m855c8e3c
          = done when: mutastic verify m855c8e3c passes
        """.replace("m855c8e3c", boundary().id()));
  }

  @Test
  void theSummaryEndsOnTheScore() {
    var output = text(false).render(report(missed(boundary())), Optional.empty(), Duration.ofMillis(500));
    var lines = output.strip().lines().toList();

    assertThat(lines.get(lines.size() - 3)).isEqualTo("    Score  0.0%  threshold 80 ✗");
    assertThat(lines.getLast()).isEqualTo("Next: mutastic run --only=<id> · mutastic verify <id>");
    assertThat(output).contains("  Mutants  ✓ 0 caught · ✗ 1 missed · ○ 0 not covered · ⏱ 0 timed out (1)",
        " Worst at  Pricing.discount() 1 ✗");
  }

  @Test
  void conciseIsOneLinePerMiss() {
    var output = text(true).render(report(missed(boundary())), Optional.empty(), Duration.ZERO);

    assertThat(output.lines().findFirst().orElseThrow()).isEqualTo(
        "✗ src/main/java/com/example/Pricing.java:5 %s no test checks the edge of `>=` in `discount()`".formatted(boundary().id()));
  }

  @Test
  void plainOutputHasNoEscapes() {
    var output = text(false).render(report(missed(boundary())), Optional.empty(), Duration.ZERO);

    assertThat(output).doesNotContain("\u001b[");
  }

  @Test
  void colourGoesOnlyOnTheMissAndTheScore() {
    var card = new HumanText(sources, 80, Style.COLOUR, false).card(missed(boundary()));

    assertThat(card).contains("\u001b[1;31m✗ missed[boundary]\u001b[0m");
    assertThat(card).contains("note: covered by PricingTest#bigOrders"); // the evidence stays plain
  }

  @Test
  void cardsAreGroupedByFileWorstMethodFirst() {
    var once = missed(new Mutant("com/example/Pricing", "total", "(I)I", 0, 9, Operator.MATH, "IADD", "ISUB", Optional.empty()));
    var twiceA = missed(boundary());
    var twiceB = missed(new Mutant("com/example/Pricing", "discount", "(I)I", 1, 6, Operator.MATH, "IDIV", "IMUL",
        Optional.empty()));
    var nested = missed(new Mutant("com/example/Pricing$Tier", "rate", "()I", 0, 7, Operator.RETURN_DEFAULT, "IRETURN",
        "return default", Optional.empty()));

    assertThat(HumanText.grouped(List.of(once, twiceA, nested, twiceB))).containsExactly(twiceA, twiceB, once, nested);

    var output = text(false).render(report(once, twiceA, nested, twiceB), Optional.empty(), Duration.ZERO);
    assertThat(output).startsWith("❯ src/main/java/com/example/Pricing.java  4 missed in 3 methods\n");
    assertThat(output.lines().filter(line -> line.startsWith("❯"))).hasSize(1);
  }

  @Test
  void locationsLinkToTheFileWhenAsked() throws Exception {
    var card = new HumanText(sources, 80, Style.PLAIN.withLinks(), false).card(missed(boundary()));
    var uri = project.resolve("src/main/java/com/example/Pricing.java").toRealPath().toUri();

    assertThat(card).contains("--> \u001b]8;;" + uri + "\u001b\\src/main/java/com/example/Pricing.java:5\u001b]8;;\u001b\\");
  }

  private HumanText text(boolean concise) {
    return new HumanText(sources, 80, Style.PLAIN, concise);
  }

  private static Mutant boundary() {
    return new Mutant("com/example/Pricing", "discount", "(I)I", 0, 5, Operator.BOUNDARY, "IF_ICMPLT", "IF_ICMPLE",
        Optional.empty());
  }

  private static MutantResult missed(Mutant mutant) {
    return new MutantResult(mutant, Status.MISSED, List.of("com.example.PricingTest#bigOrders"), Optional.empty(),
        Optional.empty(), Optional.empty(), 1, Optional.empty(), Duration.ofMillis(5));
  }

  private static Report report(MutantResult... results) {
    return new Report("sample", 17, List.of(results), 3, List.of(), Duration.ofMillis(200), 1, 1, Duration.ofMillis(400));
  }
}
