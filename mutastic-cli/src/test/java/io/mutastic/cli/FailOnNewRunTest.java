package io.mutastic.cli;

import static io.mutastic.cli.Samples.mutastic;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `run --fail-on-new` on the pricing sample, whose one miss is m855c8e3c and whose score, 90.9%,
/// is held to a threshold of 95 it can't reach: with the miss in the baseline the run passes all
/// the same, and with it taken out it's new, and fails the run, named, in every format.
class FailOnNewRunTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final String MISS = "m855c8e3c";

  @TempDir Path work;

  private Path project;

  @BeforeEach
  void aCopyOfPricing() throws Exception {
    project = Samples.copy(SAMPLE, work.resolve("pricing"));
  }

  @Test
  void aMissInTheBaselinePassesARunTheThresholdWouldFail() {
    mutastic("baseline", project.toString());

    var unjudged = run("--format=agent");
    var agent = run("--format=agent", "--fail-on-new");
    var human = run("--format=concise", "--color=never", "--fail-on-new");
    var jsonl = run("--format=jsonl", "--fail-on-new");

    assertThat(unjudged.exit()).isEqualTo(Main.FAILED);
    assertThat(agent.exit()).as(agent.err()).isEqualTo(Main.PASSED);
    assertThat(agent.out())
        .startsWith(
            "mutastic: PASS · no new misses, 1 in the baseline · score 90.9% (threshold not"
                + " applied) · ");
    assertThat(human.exit()).isEqualTo(Main.PASSED);
    assertThat(human.out()).contains("90.9%  [PASS] No new misses: 1 in the baseline.");
    assertThat(jsonl.exit()).isEqualTo(Main.PASSED);
    assertThat(jsonl.out())
        .contains("\"threshold\":95,\"passed\":true,\"failOnNew\":true,\"newMisses\":[]")
        .contains("\"inBaseline\":1");
  }

  /// A miss the baseline doesn't hold is new, whatever the score: here the threshold is one the
  /// score reaches.
  @Test
  void aMissNotInTheBaselineFailsTheRunNamed() throws Exception {
    mutastic("baseline", project.toString());
    var baseline = project.resolve(Baseline.FILE);
    Files.writeString(
        baseline, Files.readString(baseline).replaceFirst("(?s)  - id: " + MISS + ".*", ""));

    var agent = mutastic("run", project.toString(), "--format=agent", "--fail-on-new");
    var human =
        mutastic("run", project.toString(), "--format=concise", "--color=never", "--fail-on-new");
    var jsonl = mutastic("run", project.toString(), "--format=jsonl", "--fail-on-new");

    assertThat(agent.exit()).as(agent.err()).isEqualTo(Main.FAILED);
    assertThat(agent.out())
        .startsWith(
            "mutastic: FAIL · 1 new miss, not in the baseline (0 in it): %s · score 90.9%%"
                .formatted(MISS))
        .contains("1. missed %s: ".formatted(MISS));
    assertThat(human.exit()).isEqualTo(Main.FAILED);
    assertThat(human.out())
        .contains("[FAIL] 1 new miss, not in the baseline (0 in it): %s.".formatted(MISS));
    assertThat(jsonl.exit()).isEqualTo(Main.FAILED);
    assertThat(jsonl.out())
        .contains("\"passed\":false,\"failOnNew\":true,\"newMisses\":[\"%s\"]".formatted(MISS));
  }

  /// With no baseline, every miss would be new or none would, and either is a guess: it's a usage
  /// error, saying what writes one, before the build is read.
  @Test
  void withNoBaselineItSaysWhatWritesOne() {
    var run = run("--format=agent", "--fail-on-new");

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    // For an agent, the error is also stdout's one event.
    assertThat(run.out().lines())
        .singleElement(org.assertj.core.api.InstanceOfAssertFactories.STRING)
        .contains("\"reason\":\"run-failed\"", "\"code\":\"no-baseline\"");
    assertThat(run.err())
        .startsWith("mutastic: --fail-on-new needs a baseline to tell new misses from")
        .contains("`mutastic baseline` writes one");
  }

  private Samples.Result run(String... options) {
    var args = new java.util.ArrayList<>(java.util.List.of("run", project.toString()));
    args.add("--threshold=95");
    args.addAll(java.util.List.of(options));
    return mutastic(args.toArray(String[]::new));
  }
}
