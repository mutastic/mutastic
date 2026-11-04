package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// End to end on `samples/pricing`, a JDK 17 Gradle project: Gradle reads the setup, the engine
/// runs on 25 and the tests on 17, and the loop an agent follows closes a gap.
class PricingSampleTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");

  @TempDir Path work;

  @Test
  void findsTheMissedEdgeAndVerifiesTheFix() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var run = Samples.mutastic("run", project.toString(), "--format=jsonl");

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.PASSED);
    assertThat(run.out()).contains("\"reason\":\"run-finished\",\"jdk\":17");
    assertThat(run.out())
        .contains("\"Killed\":8,\"Survived\":1,\"Timeout\":0,\"NoCoverage\":0,\"Ignored\":2");
    assertThat(run.out()).contains("\"statusReason\":\"equivalent: already returns the default\"");
    var missed =
        run.out()
            .lines()
            .filter(line -> line.contains("\"status\":\"Survived\""))
            .findFirst()
            .orElseThrow();
    assertThat(missed)
        .contains("\"line\":17")
        .contains("\"original\":\">=\",\"replacement\":\">\"");
    assertThat(missed)
        .contains("\"inputs\":{\"total\":[50,250]}"); // what the tests tried: never 100
    var id = missed.replaceAll(".*\"id\":\"(m[0-9a-f]{8})\".*", "$1");

    assertThat(Samples.mutastic("verify", id, project.toString()).exit()).isEqualTo(Main.FAILED);
    var caught =
        run.out()
            .lines()
            .filter(line -> line.contains("\"status\":\"Killed\""))
            .findFirst()
            .orElseThrow()
            .replaceAll(".*\"id\":\"(m[0-9a-f]{8})\".*", "$1");
    // Several at once pass only when every one is caught.
    var both = Samples.mutastic("verify", id, caught, project.toString());
    assertThat(both.exit()).isEqualTo(Main.FAILED);
    assertThat(both.out()).contains(id + " is still missed").contains(caught + " is caught by");

    // What the advice asks for: a test at the edge, and no production change.
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    Files.writeString(
        test,
        Files.readString(test)
            .replaceFirst(
                "\\}\\s*$",
                """

          @Test
          void anOrderOfExactlyOneHundredGetsTheDiscount() {
            assertEquals(10, new Pricing().discount(100));
          }
        }
        """));

    var verified = Samples.mutastic("verify", id, caught, project.toString());
    assertThat(verified.exit()).isEqualTo(Main.PASSED);
    assertThat(verified.out())
        .contains(
            id
                + " is caught by com.example.pricing.PricingTest#anOrderOfExactlyOneHundredGetsTheDiscount");

    // A skipped mutant has nothing to catch: verify says why it's skipped, and passes, rather
    // than send anyone looking for the test that should reach it.
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
  void agentTextLeadsWithTheVerdict() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var run = Samples.mutastic("run", project.toString(), "--format=agent");

    assertThat(run.out().lines().findFirst().orElseThrow())
        .isEqualTo(
            "mutastic: PASS · score 88.9% (threshold 80) · 1 missed, 0 not covered, 8 caught of 9 mutants (2 more skipped)");
    assertThat(run.out()).contains("   - if (total >= 100) {", "   + if (total > 100) {");
  }

  @Test
  void theDefaultOffATerminalIsOneLinePerMissAndASummary() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var run = Samples.mutastic("run", project.toString());

    assertThat(run.out())
        .contains(
            " ✗ src/main/java/com/example/pricing/Pricing.java:17 no test checks discount() at total == 100 m855c8e3c");
    assertThat(
            run.out()
                .strip()
                .lines()
                .filter(line -> line.contains("Score"))
                .findFirst()
                .orElseThrow())
        .isEqualTo("     Score  88.9%  threshold 80");
    assertThat(run.out().strip().lines().toList())
        .contains(" [PASS] 1 missed, above the threshold.");
    // Progress goes to stderr, so stdout is only the result: in a log, a line per file as it
    // finishes.
    assertThat(run.err())
        .contains(
            "mutastic dev · reading the Gradle build…",
            "coverage  3 tests on JDK 17",
            " ❯ [pricing] src/main/java/com/example/pricing/Pricing.java (11 mutants | 1 missed)",
            "   × discount() :17 >= becomes >");
  }

  @Test
  void aBrokenBuildIsAUsageErrorNotACrash() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    java.nio.file.Files.writeString(project.resolve("build.gradle.kts"), "this is not kotlin");

    var run = Samples.mutastic("run", project.toString());

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err()).contains("Gradle couldn't build");
  }

  @Test
  void theSuggestedTestPastedInCatchesTheMutant() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var run = Samples.mutastic("run", project.toString(), "--format=jsonl");
    var missed =
        run.out()
            .lines()
            .filter(line -> line.contains("\"status\":\"Survived\""))
            .findFirst()
            .orElseThrow();
    var id = missed.replaceAll(".*\"id\":\"(m[0-9a-f]{8})\".*", "$1");
    var code =
        missed.replaceAll(".*\"code\":\"((?:[^\"\\\\]|\\\\.)*)\".*", "$1").replace("\\n", "\n");

    assertThat(code)
        .isEqualTo(
            """
        @Test
        void discountWithTotal100() {
          assertEquals(10, new Pricing().discount(100)); // today 10 · with this mutant 0
        }""");
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    Files.writeString(
        test, Files.readString(test).replaceFirst("\\}\\s*$", "\n" + code.indent(2) + "}\n"));

    var verified = Samples.mutastic("verify", id, project.toString());
    assertThat(verified.exit()).as(verified.out() + verified.err()).isEqualTo(Main.PASSED);
    assertThat(verified.out())
        .contains("is caught by com.example.pricing.PricingTest#discountWithTotal100");
  }

  @Test
  void noSuggestionsMeansNoUserCodeRunsOutsideTheTests() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var run = Samples.mutastic("run", project.toString(), "--format=jsonl", "--no-suggestions");

    assertThat(run.out()).doesNotContain("suggestedTest");
  }

  @Test
  void aRunWritesTheReportFilesUnlessToldNot() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var run = Samples.mutastic("run", project.toString());
    var none = Samples.copy(SAMPLE, work.resolve("quiet"));
    Samples.mutastic("run", none.toString(), "--report=none");

    assertThat(project.resolve("build/mutastic/mutation.json"))
        .content()
        .startsWith("{\"$schema\":");
    assertThat(project.resolve("build/mutastic/mutation.html"))
        .content()
        .contains("<mutation-test-report-app");
    assertThat(run.out())
        .contains("  Reports  build/mutastic/mutation.json · build/mutastic/mutation.html");
    assertThat(none.resolve("build/mutastic/mutation.json")).doesNotExist();
  }
}
