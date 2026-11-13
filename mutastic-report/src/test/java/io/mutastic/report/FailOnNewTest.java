package io.mutastic.report;

import static io.mutastic.report.AgentTextTest.boundary;
import static io.mutastic.report.AgentTextTest.removal;
import static io.mutastic.report.AgentTextTest.report;
import static io.mutastic.report.AgentTextTest.result;
import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `run --fail-on-new` in each format: the verdict is the misses the baseline doesn't hold, not the
/// score, and each format says which they are.
class FailOnNewTest {
  @TempDir Path project;

  private SourceView sources;

  /// A missed mutant the baseline holds, one it doesn't, and a caught one: a score of 33.3%.
  private final Report report =
      report(
          List.of(
              result(boundary(0), Status.MISSED),
              result(removal(1, "GOTO"), Status.NOT_COVERED),
              result(boundary(2), Status.CAUGHT)),
          List.of(),
          List.of());

  private final String known = boundary(0).id();
  private final String added = removal(1, "GOTO").id();

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

  /// A mutant no test reaches is a miss as the baseline counts them: one it doesn't hold is new.
  @Test
  void whatIsNewIsWhatNoTestCatchesAndTheBaselineDoesntHold() {
    var judged = new FailOnNew(Set.of(known));

    assertThat(judged.newMisses(report)).containsExactly(added);
    assertThat(judged.inBaseline(report)).isEqualTo(1);
    assertThat(judged.passes(report)).isFalse();
    assertThat(new FailOnNew(Set.of(known, added)).passes(report)).isTrue();
  }

  @Test
  void theSummarySaysNoNewMissesWhateverTheScore() {
    var output =
        human().failingOnNew(new FailOnNew(Set.of(known, added))).render(report, none(), run());

    assertThat(output)
        .contains("33.3%  threshold not applied (new misses decide)")
        .contains("[PASS] No new misses: 2 in the baseline.")
        .doesNotContain("FAIL");
  }

  @Test
  void theSummaryNamesTheNewMisses() {
    var output = human().failingOnNew(new FailOnNew(Set.of(known))).render(report, none(), run());

    assertThat(output)
        .contains("[FAIL] 1 new miss, not in the baseline (1 in it): %s.".formatted(added));
  }

  @Test
  void theAgentsFirstLineIsTheVerdictOnNewMisses() {
    var passed =
        new AgentText(sources, 80).failingOnNew(new FailOnNew(Set.of(known, added))).render(report);
    var failed =
        new AgentText(sources, 80).failingOnNew(new FailOnNew(Set.of(known))).render(report);

    assertThat(passed)
        .startsWith(
            "mutastic: PASS · no new misses, 2 in the baseline · score 33.3% (threshold not"
                + " applied;");
    assertThat(failed)
        .startsWith(
            "mutastic: FAIL · 1 new miss, not in the baseline (1 in it): %s · score 33.3%%"
                    .formatted(added)
                + " (threshold not applied;");
  }

  /// The agent's entries put the new misses first, and the baseline's after, as `next` queues them.
  @Test
  void theAgentsEntriesPutTheNewMissesFirst() {
    var missed =
        report(
            List.of(result(boundary(0), Status.MISSED), result(removal(1, "GOTO"), Status.MISSED)),
            List.of(),
            List.of());
    var agent = new AgentText(sources, 80).eachMutant(true);
    var first = firstEntry(agent.render(missed));
    var other = first.equals(known) ? added : known;

    var judged = agent.failingOnNew(new FailOnNew(Set.of(first))).render(missed);

    assertThat(firstEntry(judged)).isEqualTo(other);
  }

  /// On GitHub Actions, the job summary's heading is the same verdict, not the score's.
  @Test
  void theJobSummaryLeadsWithTheVerdictOnNewMisses() {
    var actions = new GitHubActions(project, sources);

    assertThat(actions.summary(report, 80, Optional.of(new FailOnNew(Set.of(known, added)))))
        .startsWith(
            "### mutastic: PASS · no new misses, 2 in the baseline · score 33.3% (threshold not"
                + " applied)\n");
    assertThat(actions.summary(report, 80, Optional.of(new FailOnNew(Set.of(known)))))
        .startsWith("### mutastic: FAIL · 1 new miss, not in the baseline (1 in it): " + added);
  }

  @Test
  void runFinishedSaysTheRunWasJudgedOnNewMisses() {
    var bytes = new ByteArrayOutputStream();
    var events =
        new JsonEvents(new PrintStream(bytes, true, StandardCharsets.UTF_8), sources, 80)
            .failingOnNew(new FailOnNew(Set.of(known)));

    events.runFinished(report, none());

    var finished = bytes.toString(StandardCharsets.UTF_8);
    Schemas.checkEvents(finished);
    assertThat(finished)
        .contains("\"threshold\":80,\"passed\":false,\"failOnNew\":true")
        .contains("\"newMisses\":[\"%s\"],\"inBaseline\":1".formatted(added));
  }

  /// Without it, `run-finished` is as it was: no field a reader would have to skip.
  @Test
  void runFinishedWithoutItHasNoneOfItsFields() {
    var bytes = new ByteArrayOutputStream();
    new JsonEvents(new PrintStream(bytes, true, StandardCharsets.UTF_8), sources, 0)
        .runFinished(report, none());

    var finished = bytes.toString(StandardCharsets.UTF_8);
    Schemas.checkEvents(finished);
    assertThat(finished)
        .contains("\"passed\":true")
        .doesNotContain("failOnNew", "newMisses", "inBaseline");
  }

  private HumanText human() {
    return new HumanText(sources, 80, Style.PLAIN, HumanText.Detail.CONCISE, Vocabulary.PLAIN);
  }

  private static HumanText.Run run() {
    return new HumanText.Run(LocalTime.of(9, 30, 5), Duration.ofMillis(500), List.of());
  }

  private static Optional<io.mutastic.api.Comparison> none() {
    return Optional.empty();
  }

  /// The id of the first entry, `1. missed m855c8e3c: …`.
  private static String firstEntry(String agentText) {
    var entry =
        java.util.regex.Pattern.compile(
                "^1\\. missed (?<id>m[0-9a-f]{8})", java.util.regex.Pattern.MULTILINE)
            .matcher(agentText);
    assertThat(entry.find()).as(agentText).isTrue();
    return entry.group("id");
  }
}
