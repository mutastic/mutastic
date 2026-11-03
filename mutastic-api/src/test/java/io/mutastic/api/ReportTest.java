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

  @Test
  void replacedWorkersAreTheExtraStarts() {
    var report =
        new Report(
            "pricing", 17, List.of(), 1, List.of(), Duration.ZERO, 2, 5, Duration.ofSeconds(1));

    assertThat(report.replaced()).isEqualTo(3);
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
