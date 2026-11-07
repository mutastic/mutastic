package io.mutastic.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReportTest {
  @Test
  void skippedMutantsAreLeftOutOfTheScore() {
    var report = report(Status.CAUGHT, Status.MISSED, Status.SKIPPED, Status.SKIPPED);

    assertThat(report.scored()).isEqualTo(2);
    assertThat(report.score()).hasValue(50.0);
  }

  @Test
  void timedOutCountsAsDetectedAndNotCoveredDoesNot() {
    var report = report(Status.TIMED_OUT, Status.CAUGHT, Status.NOT_COVERED, Status.MISSED);

    assertThat(report.score()).hasValue(50.0);
    assertThat(report.count(Status.NOT_COVERED)).isEqualTo(1);
  }

  /// The covered score leaves out what no test reaches, and with nothing reached there is none.
  @Test
  void theCoveredScoreIsOfWhatTestsReach() {
    var report =
        report(Status.TIMED_OUT, Status.CAUGHT, Status.NOT_COVERED, Status.MISSED, Status.SKIPPED);

    assertThat(report.score()).hasValue(50.0);
    assertThat(report.coveredScore())
        .hasValueCloseTo(66.67, org.assertj.core.data.Offset.offset(0.01));
    assertThat(report(Status.NOT_COVERED, Status.SKIPPED).coveredScore()).isEmpty();
  }

  @Test
  void exactlyTheThresholdPasses() {
    var report = report(Status.CAUGHT, Status.MISSED);

    assertThat(report.passes(50)).isTrue();
    assertThat(report.passes(51)).isFalse();
  }

  @Test
  void nothingToScoreHasNoScoreAndPasses() {
    var report = report(Status.SKIPPED);

    assertThat(report.score()).isEmpty();
    assertThat(report.passes(100)).isTrue();
  }

  /// A test that caught mutants and fails on its own fails the run, whatever the score, where a
  /// test that was only flaky before any mutant doesn't; and merging another module's run keeps it.
  @Test
  void aTestThatFailsAloneFailsTheRunAndSurvivesAMerge() {
    var healthy = report(Status.CAUGHT, Status.CAUGHT);
    var alone =
        new FlakyTest("com.example.PricingTest#a", List.of(), List.of(), List.of()).failingAlone();
    var flaky = new FlakyTest("com.example.PricingTest#b", List.of(), List.of(), List.of());
    var failing = withFlaky(healthy, alone, flaky);

    assertThat(withFlaky(healthy, flaky).passes(100)).isTrue();
    assertThat(failing.score()).hasValue(100.0);
    assertThat(failing.passes(0)).isFalse();
    assertThat(failing.failingAlone()).containsExactly(alone);
    assertThat(healthy.merge(failing).failingAlone()).containsExactly(alone);
    assertThat(failing.merge(healthy).passes(0)).isFalse();
  }

  private static Report withFlaky(Report report, FlakyTest... flaky) {
    var evidence = List.of(flaky);
    return new Report(
        report.project(),
        report.jdk(),
        report.results(),
        report.tests(),
        evidence.stream().map(FlakyTest::test).toList(),
        report.coverage(),
        report.workers(),
        report.started(),
        report.took(),
        report.audited(),
        report.scope(),
        report.staleIgnores(),
        evidence);
  }

  @Test
  void replacedWorkersAreTheExtraStarts() {
    var report =
        new Report(
            "pricing", 17, List.of(), 1, List.of(), Duration.ZERO, 2, 5, Duration.ofSeconds(1));

    assertThat(report.replaced()).isEqualTo(3);
    // Fewer started than there were workers for: none replaced, not "-1 replaced".
    var fewer =
        new Report(
            "pricing", 17, List.of(), 1, List.of(), Duration.ZERO, 4, 3, Duration.ofSeconds(1));
    assertThat(fewer.replaced()).isZero();
  }

  private static Report report(Status... statuses) {
    var results = new ArrayList<MutantResult>();
    for (int i = 0; i < statuses.length; i++) {
      var mutant =
          new Mutant(
              "com/example/Pricing",
              "discount",
              "(I)I",
              i,
              5,
              BuiltIn.BOUNDARY,
              "IF_ICMPLT",
              "IF_ICMPLE",
              statuses[i] == Status.SKIPPED ? Optional.of("user: test") : Optional.empty());
      results.add(
          new MutantResult(
              mutant,
              statuses[i],
              List.of(),
              Optional.empty(),
              Optional.empty(),
              Optional.empty(),
              1,
              Optional.empty(),
              Map.of(),
              Optional.empty(),
              Duration.ZERO));
    }
    return new Report(
        "pricing", 17, results, 1, List.of(), Duration.ZERO, 1, 1, Duration.ofSeconds(1));
  }
}
