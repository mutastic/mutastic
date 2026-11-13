package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Status;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// Each line progress prints, as `PROGRESS.md` words it, byte for byte with the other edition.
class FramesTest {
  private static final Map<Status, Integer> COUNTS =
      Map.of(Status.CAUGHT, 2610, Status.MISSED, 41, Status.NOT_COVERED, 214, Status.TIMED_OUT, 6);

  private static Snapshot of(Phase phase, String doing, int done, int total) {
    return new Snapshot(phase, doing, Optional.empty(), done, total, COUNTS);
  }

  @Test
  void theLiveLineHasTheSpinnerThePhaseWhatItsDoingAndItsClock() {
    var reading = of(Phase.READING, "the Gradle build", 0, 0);

    assertThat(Frames.live(reading, Duration.ofSeconds(1), 5, false))
        .isEqualTo("⠴ reading   the Gradle build · 1s");
    assertThat(Frames.live(reading, Duration.ofSeconds(1), 0, false)).startsWith("⠋ ");
    assertThat(Frames.live(reading, Duration.ofSeconds(1), 10, false)).startsWith("⠋ ");
  }

  @Test
  void theBuildToolsLatestLineIsUnderTheBuildingLine() {
    var building =
        new Snapshot(
            Phase.BUILDING,
            "gradle testClasses",
            Optional.of("> Task :compileJava"),
            0,
            0,
            Map.of());

    assertThat(Frames.live(building, Duration.ofSeconds(192), 5, false))
        .isEqualTo("⠴ building  gradle testClasses · 3m12s\n   └ > Task :compileJava");
    assertThat(Frames.live(building, Duration.ofSeconds(192), 5, true))
        .isEqualTo(
            "⠴ \u001b[1mbuilding\u001b[0m  gradle testClasses · 3m12s\n"
                + "\u001b[2m   └ > Task :compileJava\u001b[0m");
  }

  @Test
  void aLongBuildToolLineIsCutSoTheBlockKeepsItsHeight() {
    var building =
        new Snapshot(
            Phase.BUILDING, "mvn test-compile", Optional.of("x".repeat(200)), 0, 0, Map.of());

    var below = Frames.live(building, Duration.ZERO, 0, false).lines().toList().getLast();

    assertThat(below).hasSize("   └ ".length() + Frames.BELOW).endsWith("x…");
  }

  @Test
  void eachPhaseSaysHowFarItIs() {
    assertThat(Frames.live(of(Phase.COVERAGE, "", 72, 181), Duration.ofSeconds(242), 6, false))
        .isEqualTo("⠦ coverage  72/181 tests · 4m02s");
    assertThat(Frames.live(of(Phase.COVERAGE, "", 0, 0), Duration.ofSeconds(2), 6, false))
        .isEqualTo("⠦ coverage  running the tests · 2s");
    assertThat(Frames.live(of(Phase.MUTANTS, "", 2871, 5525), Duration.ofSeconds(1450), 9, false))
        .isEqualTo(
            "⠏ mutants   [2871/5525] ✓ 2610 caught · ✗ 41 missed · ○ 214 not covered"
                + " · ⏱ 6 timed out · 24m10s");
    assertThat(
            Frames.live(
                of(Phase.CHECKING, "running each catching test on the unmutated code", 18, 52),
                Duration.ofSeconds(38),
                0,
                false))
        .isEqualTo(
            "⠋ checking  41 missed · running each catching test on the unmutated code, 18/52 · 38s");
    assertThat(
            Frames.live(
                of(Phase.CHECKING, "writing the record and reports", 0, 0),
                Duration.ofSeconds(3),
                0,
                false))
        .isEqualTo("⠋ checking  41 missed · writing the record and reports · 3s");
  }

  @Test
  void aHeartbeatIsTheLiveLinePlain() {
    var building =
        new Snapshot(
            Phase.BUILDING, "gradle testClasses", Optional.of("> Task :test"), 0, 0, Map.of());

    assertThat(Frames.heartbeat(building, Duration.ofSeconds(30)))
        .isEqualTo("building  gradle testClasses · 30s\n");
    assertThat(Frames.heartbeat(of(Phase.MUTANTS, "", 312, 5525), Duration.ofSeconds(270)))
        .isEqualTo(
            "mutants   [312/5525] ✓ 2610 caught · ✗ 41 missed · ○ 214 not covered"
                + " · ⏱ 6 timed out · 4m30s\n");
  }

  @Test
  void eachPhaseEndsWithALine() {
    assertThat(Frames.built(5525, Duration.ofSeconds(581)))
        .isEqualTo("built     5525 mutants in 9m41s\n");
    assertThat(Frames.built(1, Duration.ofMillis(840))).isEqualTo("built     1 mutant in 840ms\n");
    assertThat(Frames.ran(of(Phase.MUTANTS, "", 5525, 5525), Duration.ofSeconds(2052)))
        .isEqualTo(
            "ran       5525 mutants in 34m12s · ✓ 2610 caught · ✗ 41 missed · ○ 214 not covered"
                + " · ⏱ 6 timed out\n");
    assertThat(Frames.checked(52, 41, Duration.ofSeconds(51)))
        .isEqualTo("checked   52 missed mutants in 51s · a test to write for 41\n");
    assertThat(Frames.checked(1, 0, Duration.ofMillis(3500)))
        .isEqualTo("checked   1 missed mutant in 3.5s · no test found to suggest\n");
  }

  @Test
  void durationsAndClocksTakeTheSummarysForm() {
    assertThat(Frames.duration(Duration.ofMillis(840))).isEqualTo("840ms");
    assertThat(Frames.duration(Duration.ofMillis(3500))).isEqualTo("3.5s");
    assertThat(Frames.duration(Duration.ofSeconds(51))).isEqualTo("51s");
    assertThat(Frames.duration(Duration.ofSeconds(581))).isEqualTo("9m41s");
    assertThat(Frames.duration(Duration.ofMinutes(62))).isEqualTo("1h02m");
    assertThat(Frames.clock(Duration.ofMillis(38_900))).isEqualTo("38s");
    assertThat(Frames.clock(Duration.ofSeconds(192))).isEqualTo("3m12s");
    assertThat(Frames.clock(Duration.ofMinutes(62))).isEqualTo("1h02m");
  }
}
