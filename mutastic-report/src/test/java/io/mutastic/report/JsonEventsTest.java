package io.mutastic.report;

import static io.mutastic.report.AgentTextTest.boundary;
import static io.mutastic.report.AgentTextTest.report;
import static io.mutastic.report.AgentTextTest.result;
import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.ChangedLines;
import io.mutastic.api.Comparison;
import io.mutastic.api.FlakyTest;
import io.mutastic.api.Ignore;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JsonEventsTest {
  @TempDir Path project;

  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
  private JsonEvents events;

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
    events =
        new JsonEvents(
            new PrintStream(bytes, true, StandardCharsets.UTF_8),
            new SourceView(project, List.of(project.resolve("src/main/java"))),
            80);
  }

  /// Every event a test here writes is one the published schema describes, field for field.
  @AfterEach
  void everyEventIsInTheSchema() {
    Schemas.checkEvents(bytes.toString(StandardCharsets.UTF_8));
  }

  /// A share's events read back are what it ran, result for result: `run --from-shards` makes the
  /// whole run's cards and report from them.
  @Test
  void aSharesEventsReadBackAsItsRun() throws Exception {
    var share = new JsonEvents.Share(2, 4, "0.6.0", "c0de");
    var caught =
        new MutantResult(
            boundary(0),
            Status.CAUGHT,
            List.of("com.example.PricingTest#a", "com.example.PricingTest#b"),
            Optional.of("com.example.PricingTest#b"),
            Optional.of("org.opentest4j.AssertionFailedError"),
            Optional.of("assertion"),
            2,
            Optional.of("com.example.PricingTest#a"),
            Map.of("total", List.of(50L, 250L)),
            Optional.empty(),
            Duration.ofMillis(7),
            Optional.of("com.example.PricingTest#c"),
            List.of("com.example.PricingTest#b"));
    var missed =
        result(boundary(1), Status.MISSED)
            .withSuggestion(
                Optional.of(
                    new io.mutastic.api.Suggestion(
                        "new Pricing().discount(100)",
                        List.of("total"),
                        List.of(100L),
                        List.of(new io.mutastic.api.Suggestion.Instance("Pricing", List.of(3L))),
                        "10",
                        "0")));
    var flaky =
        new FlakyTest("com.example.PricingTest#c", List.of("x.Y"), List.of(), List.of(), true);
    var report =
        new Report(
            "pricing",
            17,
            List.of(caught, missed),
            2,
            List.of("com.example.PricingTest#c"),
            Duration.ofMillis(100),
            2,
            3,
            Duration.ofMillis(300),
            List.of(),
            Optional.empty(),
            List.of(),
            List.of(flaky));
    var shared =
        new JsonEvents(
            new PrintStream(bytes, true, StandardCharsets.UTF_8),
            new SourceView(project, List.of(project.resolve("src/main/java"))),
            0,
            Optional.of(share));
    shared.runStarted("pricing");
    report.results().forEach(shared::mutantFinished);
    shared.runFinished(report);
    var file = project.resolve("events.jsonl");
    Files.write(file, bytes.toByteArray());

    var read = ShareEvents.read(file);

    assertThat(read.share()).contains(share);
    assertThat(read.project()).isEqualTo("pricing");
    assertThat(read.finished()).isTrue();
    assertThat(read.partial()).isEmpty();
    assertThat(read.ids()).containsExactly(caught.mutant().id(), missed.mutant().id());
    assertThat(read.result(caught.mutant())).isEqualTo(caught);
    assertThat(read.result(missed.mutant())).isEqualTo(missed);
    assertThat(read.status(missed.mutant().id())).isEqualTo(Status.MISSED);
    assertThat(List.of(read.jdk(), read.tests(), read.workers(), read.workersStarted()))
        .containsExactly(17, 2, 2, 3);
    assertThat(List.of(read.coverage(), read.took()))
        .containsExactly(Duration.ofMillis(100), Duration.ofMillis(300));
    assertThat(read.flaky()).containsExactly("com.example.PricingTest#c");
    assertThat(read.flakyEvidence()).containsExactly(flaky);
  }

  @Test
  void theRunStartsWithItsProjectAndRules() {
    events.runStarted("pricing");

    assertThat(lines())
        .containsExactly(
            "{\"schema\":\"mutastic.events/v1\",\"reason\":\"run-started\",\"project\":\"pricing\","
                + "\"rules\":\"add or change tests only, never production code\","
                + "\"rule\":\"add or change tests only, never production code\","
                + "\"decline\":\"%s\",\"edition\":\"java\"}".formatted(MutantCommand.DECLINE));
  }

  @Test
  void aMissedMutantCarriesWhereWhatAndTheCommands() {
    events.mutantFinished(result(boundary(0), Status.MISSED));

    var id = boundary(0).id();
    assertThat(lines().getFirst())
        .startsWith(
            "{\"schema\":\"mutastic.events/v1\",\"reason\":\"mutant-finished\",\"id\":\"%s\","
                .formatted(id))
        .contains("\"status\":\"Survived\",\"operator\":\"mutastic/boundary\"")
        .contains(
            "\"location\":{\"file\":\"src/main/java/com/example/Pricing.java\",\"line\":5,"
                + "\"class\":\"com.example.Pricing\",\"method\":\"discount(I)I\"}")
        .contains("\"original\":\">=\",\"replacement\":\">\"")
        .contains(
            "\"applied\":{\"level\":\"bytecode\",\"text\":\"IF_ICMPLT -> IF_ICMPLE\"},"
                + "\"bytecode\":\"IF_ICMPLT -> IF_ICMPLE\"")
        .contains("\"coveredBy\":[\"com.example.PricingTest#a\"]")
        .contains("\"testsRun\":1,\"durationMs\":3")
        .contains("mutastic verify " + id + " passes")
        .contains("\"rendered\":\"missed " + id + ": ")
        .doesNotContain("killedBy", "statusReason", "inputs");
  }

  @Test
  void aCaughtMutantSaysByWhatAndASkippedOneWhy() {
    events.mutantFinished(result(boundary(0), Status.CAUGHT));
    events.mutantFinished(result(boundary(1).skippedFor("arid: only logs"), Status.SKIPPED));

    assertThat(lines().get(0))
        .contains("\"status\":\"Killed\"")
        .contains("\"killedBy\":\"a.PricingTest#a\",\"caughtBy\":\"a.PricingTest#a\"")
        .contains(
            "\"killedWith\":\"org.opentest4j.AssertionFailedError\","
                + "\"caughtWith\":\"org.opentest4j.AssertionFailedError\"")
        .contains("\"failureOrigin\":\"assertion\"")
        .doesNotContain("rendered");
    assertThat(lines().get(1))
        .contains("\"status\":\"Ignored\"")
        .contains("\"statusReason\":\"arid: only logs\",\"skipKind\":\"arid\"");
  }

  /// A plugin's reason needn't start with a kind: the schema's kinds are all `skipKind` may be.
  @Test
  void aSkipReasonWithoutAKnownKindHasNoSkipKind() {
    events.mutantFinished(result(boundary(0).skippedFor("not worth it"), Status.SKIPPED));
    events.mutantFinished(result(boundary(1).skippedFor("plugin: its own words"), Status.SKIPPED));

    assertThat(lines().get(0))
        .contains("\"statusReason\":\"not worth it\"")
        .doesNotContain("skipKind");
    assertThat(lines().get(1)).doesNotContain("skipKind");
  }

  @Test
  void inputsAndAnUnconfirmedCatchAreKept() {
    events.mutantFinished(
        new MutantResult(
            boundary(0),
            Status.MISSED,
            List.of("com.example.PricingTest#a"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            2,
            Optional.of("com.example.PricingTest#b"),
            Map.of("total", List.of(50L)),
            Optional.empty(),
            Duration.ofMillis(3)));

    assertThat(lines().getFirst())
        .contains("\"unconfirmedCatchBy\":\"com.example.PricingTest#b\"")
        .contains("\"inputs\":{\"total\":[50]}");
  }

  @Test
  void theRunFinishesWithTotalsScoreAndVerdict() {
    events.runFinished(
        report(
            List.of(result(boundary(0), Status.MISSED), result(boundary(1), Status.CAUGHT)),
            List.of("com.example.PricingTest#f"),
            List.of(new Ignore("m1", "x", "y"))),
        Optional.empty(),
        List.of("build/mutastic/mutation.json"),
        false);

    assertThat(lines().getFirst())
        .contains(
            "\"reason\":\"run-finished\",\"edition\":\"java\",\"jdk\":17,"
                + "\"toolchain\":{\"name\":\"jdk\",\"version\":\"17\"},\"mutants\":2")
        .contains("\"Killed\":1,\"Survived\":1,\"Timeout\":0,\"NoCoverage\":0,\"Ignored\":0")
        .contains("\"killed\":1,\"survived\":1,\"timeout\":0,\"noCoverage\":0,\"ignored\":0")
        .contains("\"score\":50.0,\"threshold\":80,\"passed\":false,\"coveredScore\":50.0")
        .contains("\"tests\":2,\"workers\":1,\"workersStarted\":1,\"coverageMs\":100")
        .contains("\"flaky\":[\"com.example.PricingTest#f\"]")
        .contains("\"reports\":[\"build/mutastic/mutation.json\"]")
        .contains("\"staleIgnores\":[\"m1\"],\"durationMs\":300")
        .doesNotContain("previousScore", "\"only\"", "aridAudit", "scope");
  }

  @Test
  void aComparisonSaysWhatChanged() {
    events.runFinished(
        report(List.of(result(boundary(0), Status.CAUGHT)), List.of(), List.of()),
        Optional.of(
            new Comparison(
                OptionalDouble.of(33.333),
                false,
                true,
                List.of(
                    new Comparison.Flip(
                        "m11111111", Status.MISSED, Status.CAUGHT, Optional.of("T#t")),
                    new Comparison.Flip(
                        "m22222222", Status.CAUGHT, Status.TIMED_OUT, Optional.empty())))));

    assertThat(lines().getFirst())
        .contains(
            "\"previousScore\":33.3,\"sameCodeAsLastRun\":false,\"sameMutasticAsLastRun\":true")
        .contains(
            "\"changedSinceLastRun\":[{\"id\":\"m11111111\",\"before\":\"Survived\","
                + "\"after\":\"Killed\",\"caughtBy\":\"T#t\"},"
                + "{\"id\":\"m22222222\",\"before\":\"Killed\",\"after\":\"Timeout\"}]");
  }

  /// Everything the last event can say of a run at once: an audit of the arid filters, a flaky
  /// test's evidence, a catch that didn't repeat, and a test that fails the same way everywhere.
  @Test
  void theLastEventSaysEverythingTheRunFoundOut() {
    var flaky =
        new FlakyTest(
                "com.example.PricingTest#f",
                List.of("java.net.SocketTimeoutException"),
                List.of("new Random()"),
                List.of())
            .failingAlone();
    var report =
        new Report(
            "pricing",
            17,
            List.of(
                thrownByTheTest("now", true),
                thrownByTheTest("code", false),
                thrownByTheTest("rate", false)),
            2,
            List.of(flaky.test()),
            Duration.ofMillis(100),
            1,
            1,
            Duration.ofMillis(300),
            List.of(result(boundary(2), Status.CAUGHT)),
            Optional.empty(),
            List.of(),
            List.of(flaky));

    events.runFinished(report);

    assertThat(lines().getFirst())
        .contains("\"failsAlone\":true,\"outside\":true")
        .contains("\"reasons\":[\"catch-not-repeated\"]")
        .contains("\"unrelatedCatches\":[{\"test\":\"com.example.PricingTest#a\"")
        .contains("\"aridAudit\":{\"ran\":1,\"caught\":1,\"caughtByAssertion\":1");
  }

  /// A catch of a mutant in `method` by an exception the test's own code threw.
  private static MutantResult thrownByTheTest(String method, boolean unconfirmed) {
    var mutant =
        new Mutant(
            "com/example/Pricing",
            method,
            "()I",
            0,
            5,
            BuiltIn.BOUNDARY,
            "IF_ICMPLT",
            "IF_ICMPLE",
            Optional.empty(),
            List.of(),
            List.of());
    var test = Optional.of("com.example.PricingTest#a");
    return new MutantResult(
        mutant,
        Status.CAUGHT,
        List.of(test.get()),
        test,
        Optional.of("java.lang.NullPointerException"),
        Optional.of("test"),
        1,
        unconfirmed ? Optional.of("com.example.PricingTest#b") : Optional.empty(),
        Map.of(),
        Optional.empty(),
        Duration.ofMillis(3));
  }

  @Test
  void oneMutantPassesWhenItIsCaughtAndNamesIt() {
    events.runFinished(
        report(List.of(result(boundary(0), Status.CAUGHT)), List.of(), List.of()),
        Optional.empty(),
        List.of(),
        true);
    events.runFinished(
        report(List.of(result(boundary(0), Status.MISSED)), List.of(), List.of()),
        Optional.empty(),
        List.of(),
        true);
    events.runFinished(report(List.of(), List.of(), List.of()), Optional.empty(), List.of(), true);

    assertThat(lines().get(0)).contains("\"passed\":true,\"only\":\"" + boundary(0).id() + "\"");
    assertThat(lines().get(1)).contains("\"passed\":false,\"only\":\"" + boundary(0).id() + "\"");
    assertThat(lines().get(2))
        .contains("\"score\":null")
        .contains("\"only\":null")
        .contains("\"coveredScore\":null");
  }

  @Test
  void aScopedRunSaysWhatItIsSince() {
    var file = project.resolve("src/main/java/com/example/Pricing.java");
    var scoped =
        new Report(
            "pricing",
            17,
            List.of(),
            2,
            List.of(),
            Duration.ofMillis(100),
            1,
            1,
            Duration.ofMillis(300),
            List.of(),
            Optional.of(new ChangedLines("main", Map.of(file, Set.of(5, 6)))),
            List.of());

    events.runFinished(scoped);

    assertThat(lines().getFirst())
        .contains(
            "\"scope\":{\"since\":\"main\",\"files\":1,\"lines\":2,\"fileCount\":1,"
                + "\"lineCount\":2}");
  }

  private List<String> lines() {
    return bytes.toString(StandardCharsets.UTF_8).lines().toList();
  }
}
