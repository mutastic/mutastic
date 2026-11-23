package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Mutant;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestTimesTest {
  @TempDir Path work;

  private static final Mutant IN_DISCOUNT = mutant("com/example/Pricing", 5);
  private static final Mutant UNREACHED = mutant("com/example/Pricing", 9);
  private static final Mutant ELSEWHERE = mutant("com/example/Shipping", 4);

  private static final TestTimes TIMES =
      new TestTimes(
          TestTimes.RUN,
          Instant.parse("2026-11-22T14:03:27.5Z"),
          "mutastic/boundary,mutastic/math",
          "c0de",
          Set.of(UNREACHED.id()),
          new TestTimes.Measured(
              Set.of("com/example/Pricing"),
              Map.of(Coverage.key(IN_DISCOUNT), Duration.ofMillis(120)),
              Set.of("com/example/Pricing#discount(I)I"),
              Duration.ofMillis(400)));

  @Test
  void whatItWritesItReadsBackToTheSecond() {
    var file = TestTimes.file(work);

    TIMES.write(file);

    assertThat(TestTimes.read(file)).contains(TIMES);
    assertThat(TIMES.at()).isEqualTo(Instant.parse("2026-11-22T14:03:27Z"));
  }

  @Test
  void anUnreadableRecordIsNoRecord() throws Exception {
    var file = TestTimes.file(work);
    Files.writeString(file, "kind\trun\nline\tbroken\n");

    assertThat(TestTimes.read(file)).isEmpty();
    assertThat(TestTimes.read(work.resolve("missing.tsv"))).isEmpty();
  }

  @Test
  void anEstimateSumsWhatEachMutantsCoveringTestsTook() {
    var estimate = TIMES.estimate(List.of(IN_DISCOUNT, UNREACHED, IN_DISCOUNT));

    assertThat(estimate).isEqualTo(new TestTimes.Estimate(3, 0, Duration.ofMillis(240)));
  }

  @Test
  void aMutantWhereNothingWasMeasuredIsCountedNotGuessed() {
    var estimate = TIMES.estimate(List.of(IN_DISCOUNT, ELSEWHERE));

    assertThat(estimate).isEqualTo(new TestTimes.Estimate(2, 1, Duration.ofMillis(120)));
  }

  @Test
  void aSkippedMutantCostsNothingWhereverItIs() {
    var estimate = TIMES.estimate(List.of(ELSEWHERE.skippedFor("arid: logging")));

    assertThat(estimate).isEqualTo(new TestTimes.Estimate(1, 0, Duration.ZERO));
  }

  @Test
  void theMutantsBeyondTheBaseAreThoseItDidNotHave() {
    assertThat(TIMES.beyondBase(List.of(IN_DISCOUNT, UNREACHED))).containsExactly(IN_DISCOUNT);
  }

  private static Mutant mutant(String className, int line) {
    return new Mutant(
        className, "discount", "(I)I", line, line, BuiltIn.MATH, "IADD", "ISUB", Optional.empty());
  }
}
