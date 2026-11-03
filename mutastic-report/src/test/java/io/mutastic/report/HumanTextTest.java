package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HumanTextTest {
  private static final HumanText.Run RUN =
      new HumanText.Run(LocalTime.of(9, 30, 5), Duration.ofMillis(500), List.of());

  @TempDir Path project;

  private SourceView sources;

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
              return total / 10;
            }
            return 0;
          }
        }
        """);
    sources = new SourceView(project, List.of(project.resolve("src/main/java")));
  }

  @Test
  void aCardNamesTheGapAndMarksTheChange() {
    var card =
        text(HumanText.Detail.FULL, Vocabulary.PLAIN)
            .card(missed(boundary(), Map.of("total", List.of(50L, 250L))));

    assertThat(card)
        .isEqualTo(
            """

         [✗ MISSED] no test checks discount() at total == 100
           ┌─ src/main/java/com/example/Pricing.java:5
           │
         4 │   public int discount(int total) {
         5 │     if (total >= 100) {
           │               ^^ changed to >
         6 │       return total / 10;
           │
           = mutant  - if (total >= 100) {
                     + if (total > 100) {
           = note: every test calls discount() away from the boundary
                   inputs seen: 50, 250 (none is 100)
                   covered by PricingTest#bigOrders (its 1 covering test still passes)
           = help: test discount() with total = 100 and assert the result

           done when  mutastic verify %1$s passes
           reproduce  mutastic run --only=%1$s
           or ignore  mutastic ignore %1$s --reason="<why>"
        """
                .formatted(boundary().id()));
  }

  @Test
  void briefKeepsTheGapTheLineAndTheEvidence() {
    var card =
        text(HumanText.Detail.BRIEF, Vocabulary.PLAIN)
            .briefCard(missed(boundary(), Map.of("total", List.of(50L))));

    assertThat(card)
        .contains(
            " 5 │     if (total >= 100) {",
            "^^ changed to >",
            "= note: every test calls discount() away",
            "inputs seen: 50 (none is 100)",
            "= id: " + boundary().id());
    assertThat(card).doesNotContain("help:", "done when", " 4 │");
  }

  @Test
  void theSummaryEndsOnTheScoreThenTheVerdict() {
    var output =
        text(HumanText.Detail.FULL, Vocabulary.PLAIN)
            .render(report(missed(boundary(), Map.of())), Optional.empty(), RUN);
    var lines = output.strip().lines().toList();

    assertThat(output)
        .contains(
            "   Classes  0 clean | 1 with misses (1)",
            "   Mutants  ✓ 0 caught · ✗ 1 missed (1)",
            " Test runs  1 (3 without coverage selection)",
            "  Start at  09:30:05");
    assertThat(lines.get(lines.size() - 4)).isEqualTo("     Score  0.0%  threshold 80");
    assertThat(lines.get(lines.size() - 2))
        .isEqualTo(" [FAIL] Score below 80: 1 missed, 0 not covered.");
    assertThat(lines.getLast())
        .isEqualTo(
            " Next: mutastic verify <id> · mutastic run --only=<id> · mutastic explain <id>");
  }

  /// No mutants is no evidence either way: it passes, but never reads as a perfect score.
  @Test
  void nothingToScoreIsNeverAPerfectScore() {
    var output =
        text(HumanText.Detail.FULL, Vocabulary.PLAIN).render(report(), Optional.empty(), RUN);

    assertThat(output)
        .contains("     Score  none (no mutants to score)  threshold 80")
        .contains(" [PASS] Nothing to measure: no mutants to score.")
        .doesNotContain("100.0%");
  }

  @Test
  void theTestsRowCountsWorkersAndReplacements() {
    var one = report(1, 1, missed(boundary(), Map.of()));
    var several = report(4, 6, missed(boundary(), Map.of()));

    assertThat(text(HumanText.Detail.FULL, Vocabulary.PLAIN).render(one, Optional.empty(), RUN))
        .contains("     Tests  3 on JDK 17 · 1 worker\n");
    assertThat(text(HumanText.Detail.FULL, Vocabulary.PLAIN).render(several, Optional.empty(), RUN))
        .contains("     Tests  3 on JDK 17 · 4 workers (2 replaced after a timeout)\n");
  }

  @Test
  void conciseAndGroupedAreOneLinePerMiss() {
    var report = report(missed(boundary(), Map.of()));

    assertThat(
            text(HumanText.Detail.CONCISE, Vocabulary.PLAIN).render(report, Optional.empty(), RUN))
        .contains(
            " ✗ src/main/java/com/example/Pricing.java:5 no test checks discount() at total == 100 "
                + boundary().id());
    assertThat(
            text(HumanText.Detail.GROUPED, Vocabulary.PLAIN).render(report, Optional.empty(), RUN))
        .contains(
            "\n src/main/java/com/example/Pricing.java\n"
                + "   discount() 1 missed\n"
                + "     5:15    ✗ no test checks discount() at total == 100 "
                + boundary().id()
                + "\n");
  }

  @Test
  void classicVocabularySaysKilledAndSurvived() {
    var output =
        text(HumanText.Detail.FULL, Vocabulary.CLASSIC)
            .render(report(missed(boundary(), Map.of())), Optional.empty(), RUN);

    assertThat(output).contains(" Survivors ", "[✗ SURVIVED]", "✗ 1 survived", "1 with survivors");
  }

  @Test
  void aFileLineAsItFinishes() {
    var line =
        text(HumanText.Detail.FULL, Vocabulary.PLAIN)
            .fileFinished("pricing", List.of(missed(boundary(), Map.of())));

    assertThat(line)
        .isEqualTo(
            """
         ❯ [pricing] src/main/java/com/example/Pricing.java (1 mutant | 1 missed) 5ms
           × discount() :5 >= becomes >
        """);
  }

  @Test
  void labelsAreBlocksInColourRoundOrOffOnlyWhenAsked() {
    var square =
        new HumanText(sources, 80, Style.COLOUR, HumanText.Detail.FULL, Vocabulary.PLAIN)
            .card(missed(boundary(), Map.of()));
    var rounded =
        new HumanText(
                sources,
                80,
                Style.COLOUR.withLabels(Style.Labels.ROUNDED),
                HumanText.Detail.FULL,
                Vocabulary.PLAIN)
            .card(missed(boundary(), Map.of()));
    var off =
        new HumanText(
                sources,
                80,
                Style.COLOUR.withLabels(Style.Labels.OFF),
                HumanText.Detail.FULL,
                Vocabulary.PLAIN)
            .card(missed(boundary(), Map.of()));

    assertThat(square).contains("\u001b[41;30;1m ✗ MISSED \u001b[0m").doesNotContain("");
    assertThat(rounded).contains("").contains("");
    // No block: the words in the label's colour, bold.
    assertThat(off).contains("\u001b[1;31m✗ MISSED\u001b[0m").doesNotContain("\u001b[41;");
  }

  @Test
  void locationsLinkToTheFileWhenAsked() throws Exception {
    var card =
        new HumanText(sources, 80, Style.PLAIN.withLinks(), HumanText.Detail.FULL, Vocabulary.PLAIN)
            .card(missed(boundary(), Map.of()));
    var uri = project.resolve("src/main/java/com/example/Pricing.java").toRealPath().toUri();

    assertThat(card)
        .contains(
            "┌─ \u001b]8;;"
                + uri
                + "\u001b\\src/main/java/com/example/Pricing.java:5\u001b]8;;\u001b\\");
  }

  @Test
  void cardsAreGroupedByFileWorstMethodFirst() {
    var once =
        missed(
            new Mutant(
                "com/example/Pricing",
                "total",
                "(I)I",
                0,
                9,
                BuiltIn.MATH,
                "IADD",
                "ISUB",
                Optional.empty()),
            Map.of());
    var twiceA = missed(boundary(), Map.of());
    var twiceB =
        missed(
            new Mutant(
                "com/example/Pricing",
                "discount",
                "(I)I",
                1,
                6,
                BuiltIn.MATH,
                "IDIV",
                "IMUL",
                Optional.empty()),
            Map.of());
    var nested =
        missed(
            new Mutant(
                "com/example/Pricing$Tier",
                "rate",
                "()I",
                0,
                7,
                BuiltIn.RETURN_DEFAULT,
                "IRETURN",
                "return default",
                Optional.empty()),
            Map.of());

    assertThat(HumanText.grouped(List.of(once, twiceA, nested, twiceB)))
        .containsExactly(twiceA, twiceB, once, nested);
    var output =
        text(HumanText.Detail.FULL, Vocabulary.PLAIN)
            .render(report(once, twiceA, nested, twiceB), Optional.empty(), RUN);
    assertThat(output.lines().filter(line -> line.startsWith(" ❯")))
        .containsExactly(" ❯ src/main/java/com/example/Pricing.java  4 missed in 3 methods");
  }

  /// The mutants of one line are one card, the boundary leading; `--each-mutant` gives each its
  /// own.
  @Test
  void theMutantsOfOneLineAreOneCard() {
    var always =
        new Mutant(
            "com/example/Pricing",
            "discount",
            "(I)I",
            1,
            5,
            BuiltIn.REMOVE_CONDITIONAL,
            "IF_ICMPLT",
            "always jump",
            Optional.empty(),
            List.of(),
            List.of("total", "100"));
    var results = report(missed(always, Map.of()), missed(boundary(), Map.of()));
    var ids = boundary().id() + "," + always.id();

    var full = text(HumanText.Detail.FULL, Vocabulary.PLAIN).render(results, Optional.empty(), RUN);
    var brief =
        text(HumanText.Detail.BRIEF, Vocabulary.PLAIN).render(results, Optional.empty(), RUN);
    var each =
        text(HumanText.Detail.FULL, Vocabulary.PLAIN)
            .eachMutant(true)
            .render(results, Optional.empty(), RUN);

    assertThat(full.lines().filter(line -> line.contains("✗ MISSED"))).hasSize(1);
    assertThat(full)
        .contains("✗ MISSED] no test checks discount() at total == 100")
        .contains("= also    ✗ ")
        .contains("· " + always.id())
        .contains("done when  mutastic verify " + ids + " passes")
        .contains("reproduce  mutastic run --only=" + ids)
        .contains("for each of " + boundary().id() + ", " + always.id());
    assertThat(brief)
        .contains("= also    ✗ ")
        .contains("ids: " + boundary().id() + ", " + always.id());
    assertThat(each.lines().filter(line -> line.contains("✗ MISSED"))).hasSize(2);
  }

  @Test
  void plainOutputHasNoEscapes() {
    var output =
        text(HumanText.Detail.FULL, Vocabulary.PLAIN)
            .render(report(missed(boundary(), Map.of())), Optional.empty(), RUN);

    assertThat(output).doesNotContain("\u001b[");
  }

  private HumanText text(HumanText.Detail detail, Vocabulary words) {
    return new HumanText(sources, 80, Style.PLAIN, detail, words);
  }

  private static Mutant boundary() {
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
        List.of("total", "100"));
  }

  private static MutantResult missed(Mutant mutant, Map<String, List<Long>> inputs) {
    return new MutantResult(
        mutant,
        Status.MISSED,
        List.of("com.example.PricingTest#bigOrders"),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        1,
        Optional.empty(),
        inputs,
        Optional.empty(),
        Duration.ofMillis(5));
  }

  private static Report report(MutantResult... results) {
    return report(1, 1, results);
  }

  private static Report report(int workers, int started, MutantResult... results) {
    return new Report(
        "sample",
        17,
        List.of(results),
        3,
        List.of(),
        Duration.ofMillis(200),
        workers,
        started,
        Duration.ofMillis(400));
  }
}
