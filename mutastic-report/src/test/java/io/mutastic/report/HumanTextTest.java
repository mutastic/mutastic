package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.ClassMutator;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import io.mutastic.output.Style;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
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
           │               ^^ >= changed to >
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

  /// Covering tests run fastest first, and their timings move with the machine's load: the card
  /// lists them the same whichever ran first, the mutant's own test class's first, then by name.
  @Test
  void aCardListsItsTestsTheSameWhicheverRanFirst() {
    var oneNight =
        List.of(
            "com.example.CheckoutTest#total",
            "com.example.PricingTest#smallOrders",
            "com.example.PricingTest#bigOrders");
    var slowerNight = oneNight.reversed();

    var card = text(HumanText.Detail.FULL, Vocabulary.PLAIN).card(coveredBy(oneNight));

    assertThat(card)
        .isEqualTo(text(HumanText.Detail.FULL, Vocabulary.PLAIN).card(coveredBy(slowerNight)))
        .contains(
            "covered by PricingTest#bigOrders, PricingTest#smallOrders, CheckoutTest#total"
                + " (all 3 covering tests still pass)");
  }

  @Test
  void briefKeepsTheGapTheLineAndTheEvidence() {
    var card =
        text(HumanText.Detail.BRIEF, Vocabulary.PLAIN)
            .briefCard(missed(boundary(), Map.of("total", List.of(50L))));

    assertThat(card)
        .contains(
            " 5 │     if (total >= 100) {",
            "^^ >= changed to >",
            "= note: every test calls discount() away",
            "inputs seen: 50 (none is 100)",
            "= id: " + boundary().id());
    assertThat(card).doesNotContain("help:", "done when", " 4 │");
  }

  /// Coverage can't place it, so most of these tests reach nothing of it: "covered by" isn't true.
  @Test
  void aMutantRunAgainstEveryTestSaysSo() {
    var stub = new WholeClassStub(ClassMutator.Reach.EVERY_TEST);
    var one = missed(stub.mutant(ClassMutator.Member.ofClass(), 3), Map.of());

    var alone = text(HumanText.Detail.FULL, Vocabulary.PLAIN).card(one);
    var two =
        text(HumanText.Detail.FULL, Vocabulary.PLAIN)
            .card(one.alsoCoveredBy(List.of("b.OtherTest#c")));

    assertThat(alone)
        .contains("no test needs `@Size`".replace("`", ""))
        .contains("run against every test (it still passes)")
        .doesNotContain("covered by");
    assertThat(two).contains("run against every test (all 2 still pass)");
  }

  @Test
  void aMutantRunAgainstItsMethodsTestsIsCoveredByThem() {
    var stub = new WholeClassStub(ClassMutator.Reach.METHOD);
    var result = missed(stub.mutant(new ClassMutator.Member("discount", "(I)I"), 4), Map.of());

    assertThat(text(HumanText.Detail.FULL, Vocabulary.PLAIN).card(result))
        .contains("covered by PricingTest#bigOrders (its 1 covering test still passes)");
  }

  @Test
  void theWorstPlacesNameAClassOrFieldAsTheSourceDoes() {
    var stub = new WholeClassStub(ClassMutator.Reach.EVERY_TEST);
    var onField = missed(stub.mutant(ClassMutator.Member.ofField("code", "I"), 3), Map.of());
    var onClass = missed(stub.mutant(ClassMutator.Member.ofClass(), 3), Map.of());
    var onMethod = missed(stub.mutant(new ClassMutator.Member("discount", "(I)I"), 4), Map.of());

    var output =
        text(HumanText.Detail.FULL, Vocabulary.PLAIN)
            .render(report(onField, onClass, onMethod), Optional.empty(), RUN);

    assertThat(output)
        .contains("Pricing.code 1 ✗")
        .contains("Pricing 1 ✗")
        .contains("Pricing.discount() 1 ✗")
        .doesNotContain("<field", "<class>");
  }

  /// `Next:` under the misses it's about, then the rows, and last the score with its verdict: the
  /// line the cursor lands under.
  @Test
  void theSummaryEndsOnTheScoreAndItsVerdict() {
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
    assertThat(lines.getLast())
        .isEqualTo("     Score  0.0%  [FAIL] Below the threshold of 80: 1 missed, 0 not covered.");
    assertThat(output)
        .contains(
            """

             Next: mutastic verify <id> · mutastic run --only=<id> · mutastic explain <id>

               Classes  0 clean | 1 with misses (1)
            """);
  }

  /// At `--threshold=0` every run passes, so the verdict names no bar to be above.
  @Test
  void aThresholdOfZeroIsNoThreshold() {
    var output =
        new HumanText(sources, 0, Style.PLAIN, HumanText.Detail.FULL, Vocabulary.PLAIN)
            .render(report(missed(boundary(), Map.of())), Optional.empty(), RUN);

    assertThat(output.strip().lines().toList().getLast())
        .isEqualTo("     Score  0.0%  [PASS] 1 missed, with no threshold set.");
  }

  /// The Duration row names each phase it timed: the build apart from the rest of the setup, and
  /// the checking after the mutants apart from them.
  @Test
  void theDurationNamesEveryPhaseTimed() {
    var untimed = report(missed(boundary(), Map.of()));
    var timed =
        new Report(
            "sample",
            17,
            untimed.results(),
            3,
            List.of(),
            Duration.ofMillis(200),
            1,
            1,
            Duration.ofMillis(400),
            List.of(),
            Optional.empty(),
            List.of(),
            List.of(),
            Optional.empty(),
            Duration.ofMillis(50));
    var built =
        new HumanText.Run(
            LocalTime.of(9, 30, 5),
            Duration.ofMillis(500),
            List.of(),
            false,
            List.of(),
            Duration.ofMillis(300));
    var text = text(HumanText.Detail.FULL, Vocabulary.PLAIN);

    assertThat(text.render(untimed, Optional.empty(), RUN))
        .contains("  Duration  900ms (setup 500ms, coverage 200ms, mutants 200ms)\n");
    assertThat(text.render(timed, Optional.empty(), built))
        .contains(
            "  Duration  900ms (setup 200ms, build 300ms, coverage 200ms, mutants 150ms,"
                + " checking 50ms)\n");
  }

  /// Writing the reports is part of the run: with thousands of tests it took longer than the
  /// mutants, and Duration said only the rest.
  @Test
  void theDurationCountsWritingTheReports() {
    var written =
        new HumanText.Run(
                LocalTime.of(9, 30, 5),
                Duration.ofMillis(500),
                List.of(Path.of("build/mutastic/mutation.json").toAbsolutePath()),
                false)
            .writtenIn(Duration.ofMillis(1200));
    var text = text(HumanText.Detail.FULL, Vocabulary.PLAIN);

    assertThat(text.render(report(missed(boundary(), Map.of())), Optional.empty(), written))
        .contains("  Duration  2.1s (setup 500ms, coverage 200ms, mutants 200ms, reports 1.2s)\n");
  }

  /// When the machine slept during the run, a line under Duration says how long: the time awake
  /// leaves it out.
  @Test
  void theDurationSaysWhenTheMachineSlept() {
    var slept =
        new HumanText.Run(
            LocalTime.of(9, 30, 5),
            Duration.ofMillis(500),
            List.of(),
            false,
            List.of(),
            Duration.ZERO,
            Optional.empty(),
            Duration.ofSeconds(233));
    var text = text(HumanText.Detail.FULL, Vocabulary.PLAIN);
    var report = report(missed(boundary(), Map.of()));

    assertThat(text.render(report, Optional.empty(), slept))
        .contains(
            "  Duration  900ms (setup 500ms, coverage 200ms, mutants 200ms)\n"
                + "            The machine slept for 3m53s during the run, which Duration leaves"
                + " out.\n"
                + "     Tests  ");
    assertThat(text.render(report, Optional.empty(), RUN)).doesNotContain("slept");
  }

  /// `--only=a,b` ends on each mutant's fate, not the first one's alone.
  @Test
  void mutantsAskedForByIdEachEndOnTheirFate() {
    var askedFor =
        new HumanText.Run(LocalTime.of(9, 30, 5), Duration.ofMillis(500), List.of(), true);
    var output =
        text(HumanText.Detail.FULL, Vocabulary.PLAIN)
            .render(
                report(missed(boundary(0), Map.of()), missed(boundary(1), Map.of())),
                Optional.empty(),
                askedFor);
    var lines = output.strip().lines().toList();

    assertThat(lines.get(lines.size() - 2)).contains(boundary(0).id() + " is still missed");
    assertThat(lines.getLast()).contains(boundary(1).id() + " is still missed");
  }

  /// `--at` counts the mutants on its lines before each one's fate, in the run's words, the fates
  /// none had left out.
  @Test
  void mutantsOnSomeLinesAreCountedBeforeTheirFates() {
    var askedFor =
        new HumanText.Run(LocalTime.of(9, 30, 5), Duration.ofMillis(500), List.of(), true);
    var report =
        report(
            missed(boundary(0), Map.of()),
            MutantResult.skipped(boundary(1).skippedFor("user: by hand (ignored)")));

    var plain =
        text(HumanText.Detail.CONCISE, Vocabulary.PLAIN)
            .at("lines 17–20 of Pricing.java")
            .render(report, Optional.empty(), askedFor);
    var classic =
        text(HumanText.Detail.CONCISE, Vocabulary.CLASSIC)
            .at("line 17 of Pricing.java")
            .render(report(missed(boundary(0), Map.of())), Optional.empty(), askedFor);

    assertThat(plain)
        .contains("\n lines 17–20 of Pricing.java: 2 mutants, 1 missed, 1 skipped\n")
        .doesNotContain("Score ");
    assertThat(classic).contains("\n line 17 of Pricing.java: 1 mutant, 1 survived\n");
    assertThat(
            text(HumanText.Detail.CONCISE, Vocabulary.PLAIN)
                .render(report, Optional.empty(), askedFor))
        .doesNotContain(" of Pricing.java:");
  }

  /// No mutants is no evidence either way: it passes, but never reads as a perfect score.
  @Test
  void nothingToScoreIsNeverAPerfectScore() {
    var output =
        text(HumanText.Detail.FULL, Vocabulary.PLAIN).render(report(), Optional.empty(), RUN);

    assertThat(output)
        .contains("     Score  none  [PASS] Nothing to measure: no mutants to score.")
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

  /// The headline is plain words with only its code bold, as in the Rust edition: a bold around it
  /// all was ended by the first code term's reset, so its words were bold up to there and plain
  /// after.
  @Test
  void aHeadlineIsPlainWordsWithItsCodeBold() {
    var card =
        new HumanText(sources, 80, Style.COLOUR, HumanText.Detail.FULL, Vocabulary.PLAIN)
            .card(missed(boundary(), Map.of()));

    assertThat(card)
        .contains(
            "\u001b[41;30;1m ✗ MISSED \u001b[0m no test checks \u001b[1mdiscount()\u001b[0m at"
                + " \u001b[1mtotal == 100\u001b[0m\n");
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

  /// Each report file is shown as typed from where mutastic ran, here the tests' directory, and
  /// with links on opens the file itself, wherever that is.
  @Test
  void reportsAreTypedFromHereAndLinkedWhole() {
    var here = Path.of("").toAbsolutePath();
    var below = here.resolve("pricing/build/mutastic/mutation.json");
    var elsewhere = project.resolve("build/mutastic/mutation.html").toAbsolutePath();
    var run =
        new HumanText.Run(
            LocalTime.of(9, 30, 5), Duration.ofMillis(500), List.of(below, elsewhere));
    var report = report(missed(boundary(), Map.of()));

    assertThat(text(HumanText.Detail.FULL, Vocabulary.PLAIN).render(report, Optional.empty(), run))
        .contains("  Reports  pricing/build/mutastic/mutation.json · " + elsewhere + "\n");
    assertThat(
            new HumanText(
                    sources, 80, Style.PLAIN.withLinks(), HumanText.Detail.FULL, Vocabulary.PLAIN)
                .render(report, Optional.empty(), run))
        .contains(
            "\u001b]8;;"
                + below.toUri()
                + "\u001b\\pricing/build/mutastic/mutation.json\u001b]8;;\u001b\\");
  }

  /// A report's path is the project's text: an ANSI escape in it is shown, not run, and the path is
  /// whole. Rust's `report.rs` has the same test.
  @Test
  void aReportsPathShowsWhatCantBeSeen() {
    var file = project.resolve("a\u001b[2Jb/" + "x".repeat(Untrusted.LONGEST) + ".json");
    var run = new HumanText.Run(LocalTime.of(9, 30, 5), Duration.ofMillis(500), List.of(file));

    var shown =
        text(HumanText.Detail.FULL, Vocabulary.PLAIN)
            .render(report(missed(boundary(), Map.of())), Optional.empty(), run);

    assertThat(shown)
        .contains("  Reports  " + Untrusted.escape(Here.shown(file)) + "\n")
        .contains("a\\x1b[2Jb/")
        .doesNotContain("\u001b");
  }

  /// With links on and mutant pages written, each id the output shows links to its page, which
  /// opens the HTML report on it; one in a link's address is left alone. Rust's `report.rs` has the
  /// same test.
  @Test
  void idsLinkToTheirPagesWhenThereAreSome() {
    var pages = project.resolve("build/mutastic/mutation-mutants").toAbsolutePath();
    var result = missed(boundary(), Map.of());
    var id = result.mutant().id();
    var run =
        new HumanText.Run(
            LocalTime.of(9, 30, 5),
            Duration.ofMillis(500),
            List.of(),
            false,
            List.of(),
            Duration.ZERO,
            Optional.of(pages));
    var linked =
        new HumanText(sources, 80, Style.PLAIN.withLinks(), HumanText.Detail.FULL, Vocabulary.PLAIN)
            .render(report(result), Optional.empty(), run);
    var plain =
        text(HumanText.Detail.FULL, Vocabulary.PLAIN).render(report(result), Optional.empty(), run);

    assertThat(linked)
        .contains(
            "verify \u001b]8;;"
                + pages.resolve(id + ".html").toUri()
                + "\u001b\\"
                + id
                + "\u001b]8;;\u001b\\ ");
    assertThat(plain).contains("verify " + id + " ").doesNotContain("\u001b");
  }

  /// With links on, the change marked under its line opens its operator's row in the docs; with
  /// them off the card is as it was.
  @Test
  void theChangeLinksToItsOperatorsDocsWhenAsked() {
    var linked =
        new HumanText(sources, 80, Style.PLAIN.withLinks(), HumanText.Detail.FULL, Vocabulary.PLAIN)
            .withDocs(new OperatorDocs("https://docs.mutastic.io/v1.0/"))
            .card(missed(boundary(), Map.of()));
    var plain =
        new HumanText(sources, 80, Style.PLAIN, HumanText.Detail.FULL, Vocabulary.PLAIN)
            .card(missed(boundary(), Map.of()));

    assertThat(linked)
        .contains(
            "\u001b]8;;https://docs.mutastic.io/v1.0/java/operators/conditions/#boundary\u001b\\^");
    assertThat(plain).doesNotContain("\u001b").doesNotContain("docs.mutastic.io");
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
                BuiltIn.RETURN_VALUE,
                "IRETURN",
                "return 0",
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
        .contains("✗ MISSED] no test checks discount() at total == 100 · " + boundary().id())
        .containsPattern("\\+ if \\((true|false)\\) \\{")
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

  /// At most seven cards a file, and the rest counted; `--each-mutant` shows them all.
  @Test
  void aFileShowsAtMostSevenCards() {
    var results = new ArrayList<MutantResult>();
    for (int line = 1; line <= HumanText.PER_FILE + 2; line++) {
      results.add(
          missed(
              new Mutant(
                  "com/example/Pricing",
                  "discount",
                  "(I)I",
                  line,
                  line,
                  BuiltIn.MATH,
                  "IADD",
                  "ISUB",
                  Optional.empty()),
              Map.of()));
    }
    var report = report(results.toArray(MutantResult[]::new));

    var capped =
        text(HumanText.Detail.BRIEF, Vocabulary.PLAIN).render(report, Optional.empty(), RUN);
    var each =
        text(HumanText.Detail.BRIEF, Vocabulary.PLAIN)
            .eachMutant(true)
            .render(report, Optional.empty(), RUN);

    assertThat(capped.lines().filter(line -> line.contains("✗ MISSED")))
        .hasSize(HumanText.PER_FILE);
    assertThat(capped)
        .contains("… and 2 more missed mutants in this file (--each-mutant shows every card)");
    assertThat(each.lines().filter(line -> line.contains("✗ MISSED")))
        .hasSize(HumanText.PER_FILE + 2);
  }

  /// Ignores are counted by the sort they were given, and three in one method suggest the rule that
  /// says it once.
  @Test
  void ignoresAreCountedBySortAndRepeatsSuggestARule() {
    var results = new ArrayList<MutantResult>();
    for (int ordinal = 0; ordinal < RepeatedIgnores.AT_LEAST; ordinal++) {
      var ignore =
          new io.mutastic.api.Ignore("m" + ordinal, "x", "totals come in tens", "unreachable");
      results.add(MutantResult.skipped(boundary(ordinal).skippedFor(ignore.skipReason())));
    }
    results.add(MutantResult.skipped(boundary(9).skippedFor("user: by hand (ignored)")));

    var output =
        text(HumanText.Detail.CONCISE, Vocabulary.PLAIN)
            .render(report(results.toArray(MutantResult[]::new)), Optional.empty(), RUN);

    assertThat(output)
        .contains("4 skipped (1 ignored, 3 ignored as unreachable)")
        .contains(
            "note: 4 ignores in com.example.Pricing#discount: one rule says it once, skip:"
                + " com.example.Pricing#discount in mutastic.yaml");
  }

  @Test
  void plainOutputHasNoEscapes() {
    var output =
        text(HumanText.Detail.FULL, Vocabulary.PLAIN)
            .render(report(missed(boundary(), Map.of())), Optional.empty(), RUN);

    assertThat(output).doesNotContain("\u001b[");
  }

  /// With `--experimental=ghosts`, a run that found none says so, apart from a run that didn't
  /// look.
  @Test
  void aGhostsRunThatFoundNoneSaysSo() {
    var none = withGhosts(report(missed(boundary(), Map.of())), Optional.of(List.of()));
    var notAsked = report(missed(boundary(), Map.of()));

    assertThat(text(HumanText.Detail.FULL, Vocabulary.PLAIN).render(none, Optional.empty(), RUN))
        .containsPattern("Ghosts +none found \\(experimental, not in the score\\)");
    assertThat(
            text(HumanText.Detail.FULL, Vocabulary.PLAIN).render(notAsked, Optional.empty(), RUN))
        .doesNotContain("Ghosts");
  }

  static Report withGhosts(Report report, Optional<List<io.mutastic.api.Ghost>> ghosts) {
    return new Report(
        report.project(),
        report.jdk(),
        report.results(),
        report.tests(),
        report.flaky(),
        report.coverage(),
        report.workers(),
        report.started(),
        report.took(),
        report.audited(),
        report.scope(),
        report.staleIgnores(),
        report.flakyEvidence(),
        ghosts);
  }

  private HumanText text(HumanText.Detail detail, Vocabulary words) {
    return new HumanText(sources, 80, Style.PLAIN, detail, words);
  }

  private static Mutant boundary() {
    return boundary(0);
  }

  private static Mutant boundary(int ordinal) {
    return new Mutant(
        "com/example/Pricing",
        "discount",
        "(I)I",
        ordinal,
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

  private static MutantResult coveredBy(List<String> tests) {
    var one = missed(boundary(), Map.of());
    return new MutantResult(
        one.mutant(),
        one.status(),
        tests,
        one.caughtBy(),
        one.failure(),
        one.failureOrigin(),
        one.testsRun(),
        one.unconfirmed(),
        one.inputs(),
        one.suggestion(),
        one.took());
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
