package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Suggested tests on `samples/suggestions`: AssertJ-style tests, a static method, `long`
/// arguments, a record made with its own constructor, a boxed parameter, constants and a field the
/// source names, and a random method that must get none.
class SuggestionsTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "suggestions");

  @TempDir Path work;

  /// A skipped mutant has nothing to catch: verify says why it's skipped, and passes, rather than
  /// send anyone looking for the test that should reach it.
  @Test
  void verifyingASkippedMutantSaysWhyAndPasses() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("suggestions"));

    var run = Samples.mutastic("run", project.toString(), "--format=jsonl");
    var skipped =
        run.out()
            .lines()
            .filter(line -> line.contains("\"status\":\"Ignored\""))
            .findFirst()
            .orElseThrow();
    var skippedId = skipped.replaceAll(".*\"id\":\"(m[0-9a-f]{8})\".*", "$1");
    var reason = skipped.replaceAll(".*\"statusReason\":\"([^\"]+)\".*", "$1");
    var skip = Samples.mutastic("verify", skippedId, project.toString());

    assertThat(skip.exit()).isEqualTo(Main.PASSED);
    assertThat(skip.out())
        .contains(skippedId + " is skipped (" + reason + "), so it isn't run and needs no test");
  }

  @Test
  void suggestionsPastedInCatchTheirMutantsAndRandomnessGetsNone() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("suggestions"));

    var run = Samples.mutastic("run", project.toString(), "--format=jsonl");
    var suggested = new LinkedHashMap<String, String>(); // mutant id → test code
    run.out()
        .lines()
        .filter(line -> line.contains("\"status\":\"Survived\""))
        .forEach(
            line -> {
              if (line.contains("\"suggestedTest\"")) {
                suggested.put(
                    line.replaceAll(".*\"id\":\"(m[0-9a-f]{8})\".*", "$1"),
                    line.replaceAll(".*\"code\":\"((?:[^\"\\\\]|\\\\.)*)\".*", "$1")
                        .replace("\\n", "\n")
                        .replace("\\\"", "\""));
              } else {
                // Suggestions pass numbers, never null, so `start == null` can't get one.
                assertThat(line)
                    .as("only the random method and the null check go without")
                    .containsAnyOf(
                        "\"method\":\"jitter(I)I\"", "\"text\":\"IFNULL -> never jump\"");
              }
            });

    // Two mutants on a side of one condition share a test: one of each. A boxed parameter is
    // named as the source names it (`chosen`), a constructor's arguments by its parameters, and
    // a value the source names is said its way: a constant the test can use, one it can't. An
    // object a covering test makes is made the same way, and says which test it follows, so a
    // field the constructor sets has the tests' value, not one mutastic picked.
    assertThat(java.util.Set.copyOf(suggested.values()))
        .containsExactlyInAnyOrder(
            """
        @Test
        void isFreeAtFreeFrom() {
          var delivery = new Delivery(20); // as DeliveryTest#bigOrdersGoFree builds it
          assertThat(delivery.isFree(20)).isTrue(); // total == freeFrom · today true · with this mutant false
        }""",
            """
        @Test
        void lateFeeAtTheDaysLateEdge() {
          assertThat(Rates.lateFee(7L)).isEqualTo(0L); // today 0L · with this mutant 5L
        }""",
            """
        @Test
        void openOnAtStart() {
          var window = new Window(1, 10L); // as WindowTest#openInTheMiddle builds it
          assertThat(window.openOn(1)).isTrue(); // day == start · today true · with this mutant false
        }""",
            """
        @Test
        void bulkJustBelowTheCountEdge() {
          assertThat(Discounts.bulk(49)).isEqualTo(0); // today 0 · with this mutant 5
        }""",
            """
        @Test
        void discountJustBelowTheTotalEdge() {
          assertThat(Discounts.discount(99)).isEqualTo(0); // today 0 · with this mutant 10
        }""",
            """
        @Test
        void isFreeWithTotal0ForFreeFrom20() {
          var delivery = new Delivery(20); // as DeliveryTest#bigOrdersGoFree builds it
          assertThat(delivery.isFree(0)).isFalse(); // today false · with this mutant true
        }""",
            """
        @Test
        void inCreditWhenBalanceIsZero() {
          assertThat(Rates.inCredit(0)).isFalse(); // today false · with this mutant true
        }""",
            """
        @Test
        void openOnAtEnd() {
          var window = new Window(1, 10L); // as WindowTest#openInTheMiddle builds it
          assertThat(window.openOn(10)).isFalse(); // day == end · today false · with this mutant true
        }""",
            """
        @Test
        void columnsAtTheChosenEdge() {
          assertThat(Discounts.columns(3)).isEqualTo(1); // today 1 · with this mutant 2
        }""",
            """
        @Test
        void openOnWithDay0ForStart1AndEnd10() {
          var window = new Window(1, 10L); // as WindowTest#openInTheMiddle builds it
          assertThat(window.openOn(0)).isFalse(); // today false · with this mutant true
        }""",
            """
        @Test
        void bulkAtBulkFrom() {
          assertThat(Discounts.bulk(50)).isEqualTo(5); // 50 is Discounts's BULK_FROM · today 5 · with this mutant 0
        }""",
            """
        @Test
        void openOnWithDay10ForStart1AndEnd10() {
          var window = new Window(1, 10L); // as WindowTest#openInTheMiddle builds it
          assertThat(window.openOn(10)).isFalse(); // today false · with this mutant true
        }""",
            """
        @Test
        void discountAtFreeFrom() {
          assertThat(Discounts.discount(Discounts.FREE_FROM)).isEqualTo(10); // FREE_FROM = 100 · today 10 · with this mutant 0
        }""");

    // Each into the test class it was written for: RatesTest, or WindowTest for the record.
    for (var code : java.util.Set.copyOf(suggested.values())) {
      var testClass =
          code.contains("new Window(")
              ? "WindowTest"
              : code.contains("new Delivery(")
                  ? "DeliveryTest"
                  : code.contains("Discounts.") ? "DiscountsTest" : "RatesTest";
      var test = project.resolve("src/test/java/com/example/rates/" + testClass + ".java");
      Files.writeString(
          test, Files.readString(test).replaceFirst("\\}\\s*$", "\n" + code.indent(2) + "}\n"));
    }
    for (Map.Entry<String, String> entry : suggested.entrySet()) {
      var verified = Samples.mutastic("verify", entry.getKey(), project.toString());
      assertThat(verified.exit())
          .as(entry.getValue() + "\n" + verified.out() + verified.err())
          .isEqualTo(Main.PASSED);
    }
  }
}
