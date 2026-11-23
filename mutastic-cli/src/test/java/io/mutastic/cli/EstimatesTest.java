package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import io.mutastic.engine.TestTimes;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;

class EstimatesTest {
  private static final String DEFAULTS = "mutastic/boundary";
  private static final String STRONGER = "mutastic/boundary,mutastic/math";

  private static final Mutant BOUNDARY = mutant(5, BuiltIn.BOUNDARY);
  private static final Mutant MATH = mutant(6, BuiltIn.MATH);
  private static final Mutant ELSEWHERE =
      new Mutant(
          "com/example/Shipping",
          "cost",
          "()I",
          0,
          4,
          BuiltIn.MATH,
          "IADD",
          "ISUB",
          Optional.empty());

  private final Estimates estimates =
      new Estimates(ids -> Map.of(DEFAULTS, "defaults", STRONGER, "stronger").get(ids));

  private static final Estimates.Now NOW = new Estimates.Now("c0de", DEFAULTS);

  @Test
  void withNoRecordItSaysHowToGetOne() {
    assertThat(estimates.of(Optional.empty(), NOW, List.of(BOUNDARY, MATH)))
        .isEqualTo(
            "no estimate: no run is recorded here yet; run once to get estimates, or add"
                + " --estimate");
  }

  @Test
  void itNamesTheRunItCameFromAndWhatTheSetAdds() {
    assertThat(
            estimates.of(
                Optional.of(times(TestTimes.RUN, "c0de", DEFAULTS)), NOW, List.of(BOUNDARY, MATH)))
        .isEqualTo(
            "estimate: 1 more mutant than defaults, up to 2.5s more of tests, from the run of 22"
                + " Nov 2026 14:03 UTC");
  }

  @Test
  void aSetWithNothingMoreSaysSo() {
    assertThat(
            estimates.of(
                Optional.of(times(TestTimes.RUN, "c0de", DEFAULTS)), NOW, List.of(BOUNDARY)))
        .isEqualTo(
            "estimate: no more mutants than defaults, from the run of 22 Nov 2026 14:03 UTC");
  }

  @Test
  void otherCodeGivesNoEstimate() {
    assertThat(estimates.of(Optional.of(times(TestTimes.RUN, "0ld", DEFAULTS)), NOW, List.of(MATH)))
        .isEqualTo("no estimate: the code has changed since the run of 22 Nov 2026 14:03 UTC");
  }

  @Test
  void aRunUnderOtherOperatorsGivesNoEstimate() {
    assertThat(
            estimates.of(Optional.of(times(TestTimes.RUN, "c0de", STRONGER)), NOW, List.of(MATH)))
        .isEqualTo(
            "no estimate: the run of 22 Nov 2026 14:03 UTC was under stronger, not this project's"
                + " defaults");
  }

  @Test
  void aMutantWhereNothingWasMeasuredGivesNoEstimate() {
    var listed = List.of(BOUNDARY, MATH, ELSEWHERE);

    assertThat(estimates.of(Optional.of(times(TestTimes.RUN, "c0de", DEFAULTS)), NOW, listed))
        .isEqualTo(
            "no estimate: 1 of the 2 more mutants is in code the run of 22 Nov 2026 14:03 UTC"
                + " didn't measure; add --estimate to measure it all");
  }

  @Test
  void aSetsWholeCostNamesTheCoveragePass() {
    var times = times(TestTimes.COVERAGE_PASS, "c0de", DEFAULTS);

    assertThat(estimates.ofSet(times, "stronger", List.of(BOUNDARY, MATH)))
        .isEqualTo(
            "estimate: stronger, 2 mutants, up to 2.6s of tests, from the coverage pass of 22 Nov"
                + " 2026 14:03 UTC");
  }

  /// A record of Pricing, measured, whose base is the boundary mutant.
  private static TestTimes times(String kind, String code, String operators) {
    return new TestTimes(
        kind,
        Instant.parse("2026-11-22T14:03:27Z"),
        operators,
        code,
        Set.of(BOUNDARY.id()),
        new TestTimes.Measured(
            Set.of("com/example/Pricing"),
            Map.of(
                "com/example/Pricing#discount(I)I:5",
                Duration.ofMillis(120),
                "com/example/Pricing#discount(I)I:6",
                Duration.ofMillis(2500)),
            Set.of(),
            Duration.ofMillis(2620)));
  }

  private static Mutant mutant(int line, io.mutastic.api.Operator operator) {
    return new Mutant(
        "com/example/Pricing",
        "discount",
        "(I)I",
        line,
        line,
        operator,
        "IF_ICMPLT",
        "IF_ICMPLE",
        Optional.empty());
  }
}
