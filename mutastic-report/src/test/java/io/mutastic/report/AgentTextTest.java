package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Comparison;
import io.mutastic.api.Ignore;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AgentTextTest {
  @TempDir Path project;

  private AgentText text;

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
    text = new AgentText(new SourceView(project, List.of(project.resolve("src/main/java"))), 80);
  }

  @Test
  void theVerdictComesFirstWithEveryCount() {
    var output =
        text.render(
            report(
                List.of(
                    result(boundary(0), Status.MISSED),
                    result(boundary(1), Status.CAUGHT),
                    result(boundary(2), Status.TIMED_OUT),
                    result(boundary(3), Status.NOT_COVERED),
                    result(boundary(4).skippedFor("user: not this"), Status.SKIPPED)),
                List.of(),
                List.of()));

    assertThat(output.lines().findFirst())
        .contains(
            "mutastic: FAIL · score 50.0% (threshold 80; 66.7% where tests reach) · 1 missed, 1 not"
                + " covered, 2 caught of 4 mutants (1 more skipped)");
  }

  @Test
  void nothingToScorePasses() {
    var output = text.render(report(List.of(), List.of(), List.of()));

    assertThat(output)
        .isEqualTo(
            "mutastic: PASS · score none (no mutants to score) (threshold 80) · 0 missed, 0 not"
                + " covered, 0 caught of 0 mutants%n".formatted());
  }

  @Test
  void eachMissedMutantSaysWhereWhatAndHowToProveTheFix() {
    var missed = result(boundary(0), Status.MISSED);

    var output = text.render(report(List.of(missed), List.of(), List.of()));

    assertThat(output)
        .contains("rules: add or change tests only, never production code")
        .contains(
            "1. missed " + boundary(0).id() + ": no test checks `discount()` at `total == 100`")
        .contains("   at src/main/java/com/example/Pricing.java:5")
        .contains("   - if (total >= 100) {")
        .contains("   + if (total > 100) {")
        .contains("   covered by: PricingTest#a; all still pass with the change")
        .contains("   fix: test `discount()` with `total = 100` and assert the result")
        .contains("   done when: mutastic verify " + boundary(0).id() + " passes");
  }

  @Test
  void atMostTenEntriesAreShown() {
    var results = new ArrayList<MutantResult>();
    for (int i = 0; i < AgentText.LIMIT + 2; i++) {
      results.add(result(boundary(i), Status.MISSED));
    }

    var output = text.eachMutant(true).render(report(results, List.of(), List.of()));

    assertThat(output)
        .contains("10. missed")
        .doesNotContain("11. missed")
        .contains("2 more missed mutants: run again after fixing these");
  }

  /// The mutants of one line are one entry: one test there catches them all, so one fix and one
  /// command to prove it.
  @Test
  void theMutantsOfOneLineAreOneEntry() {
    var boundary = boundary(0);
    var always = removal(1, "always jump");
    var missed = List.of(result(always, Status.MISSED), result(boundary, Status.MISSED));

    var output = text.render(report(missed, List.of(), List.of()));
    var each = text.eachMutant(true).render(report(missed, List.of(), List.of()));

    var ids = boundary.id() + "," + always.id();
    assertThat(output)
        .contains("1. missed " + boundary.id() + ": no test checks `discount()` at `total == 100`")
        .contains("   also missed " + always.id() + ": ")
        .contains("   done when: mutastic verify " + ids + " passes")
        .contains("   reproduce: mutastic run --only=" + ids)
        .contains("   or ignore: mutastic ignore <id> --reason=\"<why>\"")
        .contains("for each of " + boundary.id() + ", " + always.id())
        .doesNotContain("2. missed");
    assertThat(each).contains("1. missed " + always.id()).contains("2. missed " + boundary.id());
  }

  @Test
  void notCoveredLinesAreCountedOncePerLine() {
    var output =
        text.render(
            report(
                List.of(
                    result(boundary(0), Status.NOT_COVERED),
                    result(boundary(1), Status.NOT_COVERED)),
                List.of(),
                List.of()));

    assertThat(output)
        .contains("not covered (no test reaches these lines):")
        .contains("- src/main/java/com/example/Pricing.java:5 discount() (2 mutants)");
  }

  /// A method no test reaches is one line, with the lines its mutants are on; `--each-mutant` lists
  /// each line.
  @Test
  void notCoveredMethodsAreOneLineEach() {
    var elsewhere =
        new Mutant(
            "com/example/Pricing",
            "discount",
            "(I)I",
            9,
            8,
            BuiltIn.RETURN_DEFAULT,
            "IRETURN",
            "return default",
            Optional.empty());
    var uncovered =
        report(
            List.of(result(boundary(0), Status.NOT_COVERED), result(elsewhere, Status.NOT_COVERED)),
            List.of(),
            List.of());

    assertThat(text.render(uncovered))
        .contains("- src/main/java/com/example/Pricing.java:5-8 discount() (2 mutants)");
    assertThat(text.eachMutant(true).render(uncovered))
        .contains("- src/main/java/com/example/Pricing.java:5 discount() (1 mutant)")
        .contains("- src/main/java/com/example/Pricing.java:8 discount() (1 mutant)");
  }

  @Test
  void notCoveredAfterMissedSaysToFixTheMissedFirst() {
    var output =
        text.render(
            report(
                List.of(
                    result(boundary(0), Status.MISSED), result(boundary(1), Status.NOT_COVERED)),
                List.of("com.example.PricingTest#flaky"),
                List.of()));

    assertThat(output)
        .contains(
            "not covered (no test reaches these lines, or only a flaky one does; fix the missed"
                + " mutants first):")
        .contains("(1 mutant)");
  }

  @Test
  void flakyTestsAreNamedInTheRightNumber() {
    var one = text.render(report(List.of(), List.of("com.example.PricingTest#a"), List.of()));
    var two =
        text.render(
            report(
                List.of(),
                List.of("com.example.PricingTest#a", "com.example.PricingTest#b"),
                List.of()));

    assertThat(one)
        .contains("flaky: PricingTest#a failed, then passed when run again")
        .contains("so its catches don't count. Fix it first: it depends");
    assertThat(two)
        .contains("flaky: PricingTest#a, PricingTest#b failed")
        .contains("so their catches don't count. Fix them first: they depend");
  }

  @Test
  void staleIgnoresAreNamed() {
    var output =
        text.render(
            report(
                List.of(),
                List.of(),
                List.of(new Ignore("m1234abcd", "if (x) {", "old"), new Ignore("m9", "y", "z"))));

    assertThat(output).contains("stale ignores: m1234abcd, m9 no longer match their code");
  }

  @Test
  void theLastRunIsComparedAndFlipsPointAtTheirTest() {
    var comparison =
        new Comparison(
            OptionalDouble.of(25),
            true,
            true,
            List.of(
                new Comparison.Flip(
                    "m1", Status.CAUGHT, Status.MISSED, Optional.of("com.example.PricingTest#a"))));

    var output =
        text.render(
            report(List.of(result(boundary(0), Status.CAUGHT)), List.of(), List.of()),
            Optional.of(comparison));

    assertThat(output)
        .contains("vs last run: 25.0% → 100.0% (same code)")
        .contains("likely flaky: PricingTest#a; with the code unchanged, whether it catches m1");
  }

  @Test
  void oneMutantAskedForGetsItsFate() {
    var caught = result(boundary(0), Status.CAUGHT);

    assertThat(render(caught))
        .isEqualTo("mutastic: %s is caught by a.PricingTest#a%n".formatted(caught.mutant().id()));
    assertThat(render(result(boundary(0), Status.TIMED_OUT)))
        .contains("is caught: its tests stopped finishing in time");
    assertThat(render(result(boundary(0), Status.NOT_COVERED))).contains("isn't reached by");
    assertThat(render(result(boundary(0).skippedFor("user: no"), Status.SKIPPED)))
        .contains("is skipped: user: no");
    assertThat(render(result(boundary(0), Status.MISSED)))
        .contains("is still missed")
        .contains("rules: ")
        .contains("missed " + boundary(0).id() + ": ");
  }

  @Test
  void namesAndInputsAreShort() {
    assertThat(AgentText.shortName("com.example.PricingTest#a")).isEqualTo("PricingTest#a");
    assertThat(AgentText.shortName("com.example.PricingTest")).isEqualTo("PricingTest");
    assertThat(AgentText.covering(List.of("a.A#a", "a.B#b", "a.C#c", "a.D#d", "a.E#e")))
        .isEqualTo("A#a, B#b, C#c and 2 more");
    assertThat(AgentText.covering(List.of("a.A#a", "a.B#b", "a.C#c"))).isEqualTo("A#a, B#b, C#c");
    assertThat(
            AgentText.inputs(
                new java.util.TreeMap<>(Map.of("a", List.of(1L, 2L), "b", List.of(3L)))))
        .isEqualTo("a = 1, 2; b = 3");
  }

  @Test
  void anUnconfirmedCatchIsCalledFlaky() {
    var missed =
        new MutantResult(
            boundary(0),
            Status.MISSED,
            List.of("com.example.PricingTest#a"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            1,
            Optional.of("com.example.PricingTest#b"),
            Map.of(),
            Optional.empty(),
            Duration.ofMillis(3));

    assertThat(text.entry(0, missed))
        .startsWith("missed ")
        .contains("all still pass with the change (PricingTest#b failed once, then passed: flaky)");
  }

  private String render(MutantResult result) {
    return text.render(report(List.of(result), List.of(), List.of()), Optional.empty(), true);
  }

  static Mutant boundary(int ordinal) {
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

  static Mutant removal(int ordinal, String replacement) {
    return new Mutant(
        "com/example/Pricing",
        "discount",
        "(I)I",
        ordinal,
        5,
        BuiltIn.REMOVE_CONDITIONAL,
        "IF_ICMPLT",
        replacement,
        Optional.empty(),
        List.of(),
        List.of("total", "100"));
  }

  static MutantResult result(Mutant mutant, Status status) {
    var caughtBy =
        status == Status.CAUGHT ? Optional.of("a.PricingTest#a") : Optional.<String>empty();
    return new MutantResult(
        mutant,
        status,
        status == Status.NOT_COVERED ? List.of() : List.of("com.example.PricingTest#a"),
        caughtBy,
        caughtBy.map(ignored -> "org.opentest4j.AssertionFailedError"),
        caughtBy.map(ignored -> "assertion"),
        1,
        Optional.empty(),
        Map.of(),
        Optional.empty(),
        Duration.ofMillis(3));
  }

  static Report report(List<MutantResult> results, List<String> flaky, List<Ignore> stale) {
    return new Report(
        "pricing",
        17,
        results,
        2,
        flaky,
        Duration.ofMillis(100),
        1,
        1,
        Duration.ofMillis(300),
        List.of(),
        Optional.empty(),
        stale);
  }
}
