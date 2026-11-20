package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/// Twins as the contract's `twins` says: mutants at one place that always do the same run once, the
/// other skipped as `twin: same as <id>`, and a twin's id still works with `verify`, `explain` and
/// `ignore`, each saying which mutant runs for it. Run as a person does, on the pricing sample with
/// a for-each loop over an array and a comparison returned as it is, the shapes javac gives twins.
/// The Rust edition checks the same patterns on its own fixture (tests/twins.rs).
class TwinsTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final JsonNode TWINS = EditionContract.read().get("twins");

  private static final String SHAPES =
      """
      package com.example.pricing;

      public final class Shapes {
        public static int sum(int[] values) {
          int total = 0;
          for (int value : values) {
            total += value;
          }
          return total;
        }

        public static boolean positive(int n) {
          return n > 0;
        }
      }
      """;

  private static final String SHAPES_TEST =
      """
      package com.example.pricing;

      import static org.junit.jupiter.api.Assertions.assertEquals;
      import static org.junit.jupiter.api.Assertions.assertTrue;

      import org.junit.jupiter.api.Test;

      class ShapesTest {
        @Test
        void sums() {
          assertEquals(3, Shapes.sum(new int[] {1, 2}));
        }

        @Test
        void onePositive() {
          assertTrue(Shapes.positive(1));
        }
      }
      """;

  @TempDir static Path work;

  private static Path project;
  private static List<JsonNode> twins;
  private static Map<String, JsonNode> byId;

  @BeforeAll
  static void aRunWithTwins() throws IOException {
    project = Samples.copy(SAMPLE, work.resolve("twins"));
    Files.writeString(project.resolve("src/main/java/com/example/pricing/Shapes.java"), SHAPES);
    Files.writeString(
        project.resolve("src/test/java/com/example/pricing/ShapesTest.java"), SHAPES_TEST);
    var run = Samples.mutastic("run", project.toString(), "--format=jsonl", "--no-suggestions");
    var events = run.out().lines().map(JsonMapper.shared()::readTree).toList();
    var finished =
        events.stream()
            .filter(event -> event.get("reason").asString().equals("mutant-finished"))
            .toList();
    byId = new java.util.HashMap<>();
    finished.forEach(event -> byId.put(event.get("id").asString(), event));
    twins = finished.stream().filter(event -> event.has("sameAs")).toList();
  }

  @Test
  void aTwinIsSkippedAsTheSameAsTheOneThatRuns() {
    var skip = Pattern.compile(TWINS.get("skip").asString());
    assertThat(twins).isNotEmpty();
    for (var twin : twins) {
      var runs = byId.get(twin.get("sameAs").asString());
      assertThat(twin.get("status").asString()).isEqualTo("Ignored");
      assertThat(twin.get("skipKind").asString()).isEqualTo("twin");
      assertThat(twin.get("statusReason").asString())
          .matches(skip)
          .isEqualTo("twin: same as " + twin.get("sameAs").asString());
      assertThat(runs).as("the mutant %s is the same as", twin.get("id")).isNotNull();
      assertThat(runs.has("skipKind")).as("whether %s is skipped", runs.get("id")).isFalse();
      assertThat(runs.get("location").get("line").asInt())
          .isEqualTo(twin.get("location").get("line").asInt());
    }
    // The loop's test always true is the same as `<` made `<=`; `return true` as the test
    // always true.
    assertThat(twins)
        .extracting(twin -> twin.get("operator").asString() + " " + description(twin))
        .contains(
            "mutastic/remove-conditional the for-each loop over `values` reads past its last"
                + " element, an ArrayIndexOutOfBoundsException",
            "mutastic/return-value returns `true` instead",
            "mutastic/return-value returns `false` instead");
  }

  @Test
  void theSummaryCountsATwinWithTheSkipped() {
    var run = Samples.mutastic("run", project.toString(), "--no-suggestions");
    assertThat(run.out()).containsPattern("Mutants  .* skipped \\(.*\\d+ twin\\)");
  }

  @Test
  void verifyRunsTheOneATwinIsTheSameAs() {
    var twin = missedTwin();
    var verified = Samples.mutastic("verify", twin.get("id").asString(), project.toString());
    var line = lineMatching(verified.out(), TWINS.get("verify").asString());

    assertThat(line.group("twin")).isEqualTo(twin.get("id").asString());
    assertThat(line.group("runs")).isEqualTo(twin.get("sameAs").asString());
    assertThat(line.group("fate")).isEqualTo("is still missed");
    assertThat(verified.exit()).isEqualTo(Main.FAILED);
    // The one that runs isn't named, so it has no line of its own.
    assertThat(verified.out()).doesNotContain("mutastic: " + line.group("runs") + " ");
  }

  @Test
  void explainShowsTheOneATwinIsTheSameAs() {
    var twin = missedTwin();
    var explained = Samples.mutastic("explain", twin.get("id").asString(), project.toString());
    var line = lineMatching(explained.out(), TWINS.get("explain").asString());

    assertThat(line.group("twin")).isEqualTo(twin.get("id").asString());
    assertThat(line.group("runs")).isEqualTo(twin.get("sameAs").asString());
    assertThat(explained.out()).contains("MISSED");
  }

  @Test
  void ignoringATwinRecordsTheDecisionForTheOneThatRuns() throws IOException {
    var copy = Samples.copy(project, work.resolve("ignoring"));
    var twin = missedTwin();
    var ignored =
        Samples.mutastic(
            "ignore", twin.get("id").asString(), copy.toString(), "--reason=tested elsewhere");
    var line = lineMatching(ignored.out(), TWINS.get("ignore").asString());

    assertThat(line.group("runs")).isEqualTo(twin.get("sameAs").asString());
    assertThat(Files.readString(copy.resolve(".mutastic/ignores.yaml")))
        .contains("id: " + twin.get("sameAs").asString())
        .doesNotContain("id: " + twin.get("id").asString());
  }

  @Test
  void anIgnoreOfATwinHoldsForTheOneThatRuns() throws IOException {
    var copy = Samples.copy(project, work.resolve("kept"));
    var twin = missedTwin();
    var main = Files.readAllLines(copy.resolve("src/main/java/com/example/pricing/Shapes.java"));
    var line = twin.get("location").get("line").asInt();
    Files.createDirectories(copy.resolve(".mutastic"));
    Files.writeString(
        copy.resolve(".mutastic/ignores.yaml"),
        """
        ignores:
          - id: %s
            at: 'com.example.pricing.Shapes#positive, line %d'
            change: '`n > 0` is always true'
            code: '%s'
            reason: 'tested elsewhere'
        """
            .formatted(twin.get("id").asString(), line, main.get(line - 1).strip()));
    var verified = Samples.mutastic("verify", twin.get("sameAs").asString(), copy.toString());

    assertThat(verified.out())
        .contains(
            "mutastic: %s is skipped (user: tested elsewhere (ignored))"
                .formatted(twin.get("sameAs").asString()));
    assertThat(verified.exit()).isEqualTo(Main.PASSED);
  }

  /// A twin whose partner is missed: the test checks `positive(1)` only.
  private static JsonNode missedTwin() {
    return twins.stream()
        .filter(
            twin ->
                byId.get(twin.get("sameAs").asString()).get("status").asString().equals("Survived"))
        .findFirst()
        .orElseThrow();
  }

  private static String description(JsonNode event) {
    return event.get("mutation").get("description").asString();
  }

  private static Matcher lineMatching(String out, String pattern) {
    var compiled = Pattern.compile(pattern);
    return out.lines()
        .map(compiled::matcher)
        .filter(Matcher::matches)
        .findFirst()
        .orElseThrow(() -> new AssertionError("no line matches " + pattern + " in\n" + out));
  }
}
