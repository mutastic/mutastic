package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/// The clocks are given, so nothing sleeps: a run the Mac slept through on 16 Nov took 4m05s by the
/// clock and 15.4s awake.
class SleptTest {
  private static final Instant START = Instant.parse("2026-11-16T09:00:00Z");

  private static final Slept CLOCKS = new Slept(START, 1_000);

  @Test
  void theClockLessTheTimeAwakeIsTheSleep() {
    var slept = CLOCKS.until(START.plusSeconds(245), 1_000 + Duration.ofMillis(15_400).toNanos());

    assertThat(slept).isEqualTo(Duration.ofMillis(229_600));
    assertThat(Slept.line(slept))
        .isEqualTo("The machine slept for 3m49s during the run, which Duration leaves out.");
  }

  @Test
  void fiveSecondsIsTheLeastWorthSaying() {
    assertThat(Slept.noticed(Duration.ofSeconds(20), Duration.ofSeconds(15)))
        .isEqualTo(Duration.ofSeconds(5));
    assertThat(Slept.noticed(Duration.ofSeconds(20), Duration.ofMillis(15_001))).isZero();
  }

  /// An hour's run that slept for a minute still reads about right.
  @Test
  void aTenthOfTheClocksTimeIsTheLeastWorthSaying() {
    assertThat(Slept.noticed(Duration.ofSeconds(100), Duration.ofSeconds(90)))
        .isEqualTo(Duration.ofSeconds(10));
    assertThat(Slept.noticed(Duration.ofSeconds(100), Duration.ofMillis(90_001))).isZero();
    assertThat(Slept.noticed(Duration.ofHours(1), Duration.ofMinutes(59))).isZero();
  }

  @Test
  void aClockSetBackIsNoSleep() {
    assertThat(CLOCKS.until(START.minusSeconds(60), 1_000 + Duration.ofSeconds(30).toNanos()))
        .isZero();
  }

  @Test
  void aRunJustStartedHasNotSlept() {
    assertThat(Slept.now().untilNow()).isZero();
  }
}
