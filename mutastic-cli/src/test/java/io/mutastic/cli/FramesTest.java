package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Status;
import io.mutastic.cli.Snapshot.Estimate;
import io.mutastic.cli.Snapshot.Worker;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/// Each line progress prints, as `PROGRESS.md` words it, at 160, 120 and 80 columns. Where the
/// other edition draws the same state, its frames tests (`progress/frames.rs`) have the same
/// fixtures and expect the same bytes.
class FramesTest {
  private static final Map<Status, Integer> COUNTS =
      Map.of(Status.CAUGHT, 2610, Status.MISSED, 41, Status.NOT_COVERED, 214, Status.TIMED_OUT, 6);

  /// 52% of the mutants' cost done, and 21 minutes left.
  private static final Estimate HALF =
      new Estimate(Optional.of(0.52), Optional.of(Duration.ofMinutes(21)));

  private static Snapshot of(Phase phase, String doing, int done, int total, long seconds) {
    return new Snapshot(
        phase,
        doing,
        Optional.empty(),
        done,
        total,
        COUNTS,
        Duration.ofSeconds(seconds),
        HALF,
        List.of(),
        true);
  }

  private static Snapshot building(long seconds) {
    return new Snapshot(
        Phase.BUILDING,
        "gradle testClasses",
        Optional.of("> Task :compileJava"),
        0,
        0,
        Map.of(),
        Duration.ofSeconds(seconds),
        Estimate.NONE,
        List.of(),
        false);
  }

  /// Eight workers: the first, seventh (slow) and eighth busy, as in the other edition's tests.
  private static List<Optional<Worker>> working() {
    var workers = new ArrayList<Optional<Worker>>(Collections.nCopies(8, Optional.empty()));
    workers.set(0, running("src/engine.rs:540 finished() && becomes ||", 4, false));
    workers.set(6, running("src/testrun.rs:114 spawn() call removed", 151, true));
    workers.set(7, running("src/cli.rs:753 line() + becomes -", 1, false));
    return workers;
  }

  private static Optional<Worker> running(String doing, long seconds, boolean slow) {
    return Optional.of(
        new Worker("m0", doing, Duration.ofSeconds(seconds), Duration.ofSeconds(40), slow, 23));
  }

  private static Snapshot mutants(long seconds) {
    return new Snapshot(
        Phase.MUTANTS,
        "",
        Optional.empty(),
        2871,
        5525,
        COUNTS,
        Duration.ofSeconds(seconds),
        HALF,
        working(),
        true);
  }

  @Test
  void eachPhaseHasALiveLineWithItsClock() {
    assertThat(Frames.live(of(Phase.READING, "the Gradle build", 0, 0, 1), 5, 120, false))
        .containsExactly("⠴ reading   the Gradle build · 1s");
    assertThat(Frames.live(building(192), 5, 120, false))
        .containsExactly("⠴ building  gradle testClasses · 3m12s", "   └ > Task :compileJava");
    assertThat(
            Frames.live(
                of(Phase.CHECKING, "running each catching test on the unmutated code", 18, 52, 38),
                5,
                120,
                false))
        .containsExactly(
            "⠴ checking  41 missed · running each catching test on the unmutated code, 18/52 · 38s");
    assertThat(Frames.live(of(Phase.READING, "", 0, 0, 1), 0, 120, false).getFirst())
        .startsWith("⠋ ");
    assertThat(Frames.live(of(Phase.READING, "", 0, 0, 1), 10, 120, false).getFirst())
        .startsWith("⠋ ");
  }

  @Test
  void inColourTheSpinnerIsGreenThePhaseBoldAndTheBuildToolsLineDim() {
    assertThat(Frames.live(building(192), 5, 120, true))
        .containsExactly(
            "\u001b[32m⠴\u001b[0m \u001b[1mbuilding\u001b[0m  gradle testClasses · 3m12s",
            "\u001b[2m   └ > Task :compileJava\u001b[0m");
  }

  @Test
  void theMutantsLineHasABarTheWeightedPercentAndWhatsLeft() {
    assertThat(Frames.live(mutants(1450), 9, 160, false))
        .containsExactly(
            "⠏ mutants   "
                + "━".repeat(21)
                + "─".repeat(19)
                + "  52% · 2871/5525 · ✓ 2610 caught · ✗ 41 missed · ○ 214 not covered"
                + " · ⏱ 6 timed out · 24m10s · ~21m left",
            "   1 [00:00:04]       src/engine.rs:540 finished() && becomes ||",
            "   2 idle",
            "   3 idle",
            "   4 idle",
            "   5 idle",
            "   6 idle",
            "   7 [00:02:31] SLOW  src/testrun.rs:114 spawn() call removed",
            "   8 [00:00:01]       src/cli.rs:753 line() + becomes -",
            "     t for what’s running");
  }

  @Test
  void aNarrowTerminalShrinksTheBarThenTheWordsThenShowsFourWorkers() {
    var wide = Frames.live(mutants(1450), 9, 120, false);
    assertThat(wide.getFirst())
        .isEqualTo(
            "⠏ mutants   "
                + "━".repeat(14)
                + "─".repeat(12)
                + "  52% · 2871/5525 · ✓ 2610 caught · ✗ 41 missed · ○ 214 · ⏱ 6"
                + " · 24m10s · ~21m left");
    assertThat(wide).allSatisfy(line -> assertThat(Fit.width(line)).isLessThanOrEqualTo(120));

    var narrow = Frames.live(mutants(1450), 9, 80, false);
    assertThat(narrow)
        .containsExactly(
            "⠏ mutants   "
                + "━".repeat(5)
                + "─".repeat(5)
                + "  52% · 2871/5525 · ✓2610 ✗41 ○214 ⏱6 · 24m10s · ~21m left",
            "   1 [00:00:04]       src/engine.rs:540 finished() && becomes ||",
            "   2 idle",
            "   3 idle",
            "   4 idle",
            "     … and 2 more running · t for what’s running");
    assertThat(narrow).allSatisfy(line -> assertThat(Fit.width(line)).isLessThanOrEqualTo(80));
  }

  @Test
  void tooNarrowForAnyBarItsLinesAreCut() {
    var lines = Frames.live(mutants(1450), 9, 50, false);

    assertThat(lines.getFirst()).isEqualTo("⠏ mutants   52% · 2871/5525 · ✓2610 ✗41 ○214 ⏱6 ·…");
    assertThat(lines).allSatisfy(line -> assertThat(Fit.width(line)).isLessThanOrEqualTo(50));
  }

  @Test
  void inColourTheBarIsGreenOnDimAndTheFatesTheirColours() {
    var line = Frames.live(mutants(1450), 9, 160, true).getFirst();

    assertThat(line)
        .isEqualTo(
            "\u001b[32m⠏\u001b[0m \u001b[1mmutants\u001b[0m   "
                + "\u001b[32m"
                + "━".repeat(21)
                + "\u001b[0m\u001b[2m"
                + "─".repeat(19)
                + "\u001b[0m  52% · 2871/5525 · \u001b[32m✓ 2610 caught\u001b[0m · "
                + "\u001b[1;31m✗ 41 missed\u001b[0m · ○ 214 not covered · "
                + "\u001b[33m⏱ 6 timed out\u001b[0m · 24m10s · ~21m left");
    assertThat(Fit.width(line)).isEqualTo(156);
  }

  @Test
  void theCoveragePassHasABarAndItsTest() {
    var test =
        new Worker(
            "",
            "com.example.DocsTest#theDocsShowWhatTheOperatorsPrint",
            Duration.ofSeconds(231),
            Duration.ZERO,
            true,
            1);
    var coverage =
        new Snapshot(
            Phase.COVERAGE,
            "",
            Optional.empty(),
            72,
            181,
            Map.of(),
            Duration.ofSeconds(242),
            Estimate.NONE,
            List.of(Optional.of(test)),
            true);

    assertThat(Frames.live(coverage, 6, 120, false))
        .containsExactly(
            "⠦ coverage  " + "━".repeat(16) + "─".repeat(24) + "  72/181 tests · 4m02s",
            "   1 [00:03:51] SLOW  com.example.DocsTest#theDocsShowWhatTheOperatorsPrint",
            "     t for what’s running");
    assertThat(Frames.live(coverage, 6, 80, false))
        .containsExactly(
            "⠦ coverage  " + "━".repeat(16) + "─".repeat(24) + "  72/181 tests · 4m02s",
            "   1 [00:03:51] SLOW  com.example.DocsTest#theDocsShowWhatTheOperatorsPrint",
            "     t for what’s running");
    assertThat(Frames.live(of(Phase.COVERAGE, "", 0, 0, 2), 6, 120, false))
        .containsExactly("⠦ coverage  listing the tests · 2s");
  }

  @Test
  void aHeartbeatIsTheLiveLinePlainInItsShortForm() {
    assertThat(Frames.heartbeat(building(30))).isEqualTo("building  gradle testClasses · 30s\n");
    assertThat(Frames.heartbeat(of(Phase.COVERAGE, "", 18, 181, 60)))
        .isEqualTo("coverage  [18/181 tests] · 1m00s\n");
    assertThat(Frames.heartbeat(of(Phase.COVERAGE, "", 0, 0, 1)))
        .isEqualTo("coverage  listing the tests · 1s\n");
    assertThat(Frames.heartbeat(of(Phase.MUTANTS, "", 2871, 5525, 270)))
        .isEqualTo(
            "mutants   [2871/5525] 52% · ✓ 2610 caught · ✗ 41 missed · ○ 214 not covered"
                + " · ⏱ 6 timed out · 4m30s · ~21m left\n");
    assertThat(
            Frames.heartbeat(
                of(Phase.CHECKING, "running each catching test on the unmutated code", 18, 52, 30)))
        .isEqualTo("checking  41 missed · 18/52 · 30s\n");
    assertThat(Frames.heartbeat(of(Phase.CHECKING, "writing the record and reports", 0, 0, 3)))
        .isEqualTo("checking  41 missed · 3s\n");
  }

  @Test
  void beforeThereIsAnEstimateTheMutantsSayOnlyTheirCount() {
    var counted =
        new Snapshot(
            Phase.MUTANTS,
            "",
            Optional.empty(),
            312,
            5525,
            COUNTS,
            Duration.ofSeconds(9),
            Estimate.NONE,
            List.of(),
            false);

    assertThat(Frames.heartbeat(counted))
        .isEqualTo(
            "mutants   [312/5525] · ✓ 2610 caught · ✗ 41 missed · ○ 214 not covered"
                + " · ⏱ 6 timed out · 9s\n");
    assertThat(Frames.live(counted, 0, 160, false).getFirst())
        .isEqualTo(
            "⠋ mutants   "
                + "━".repeat(2)
                + "─".repeat(38)
                + "  312/5525 · ✓ 2610 caught · ✗ 41 missed · ○ 214 not covered"
                + " · ⏱ 6 timed out · 9s");
  }

  @Test
  void whatsLeftIsSteady() {
    assertThat(Frames.left(Duration.ofMinutes(21))).isEqualTo("~21m left");
    assertThat(Frames.left(Duration.ofSeconds(1289))).isEqualTo("~21m left");
    assertThat(Frames.left(Duration.ofSeconds(254))).isEqualTo("~4m10s left");
    assertThat(Frames.left(Duration.ofSeconds(41))).isEqualTo("~40s left");
    assertThat(Frames.left(Duration.ofSeconds(1))).isEqualTo("~5s left");
  }

  @Test
  void fittingCountsWhatShowsAndClosesTheColour() {
    var line = "\u001b[32m✓ 10 caught\u001b[0m and more";

    assertThat(Fit.width(line)).isEqualTo(20);
    assertThat(Fit.cut(line, 8)).isEqualTo("\u001b[32m✓ 10 ca…\u001b[0m");
    assertThat(Fit.cut("short", 8)).isEqualTo("short");
    assertThat(Fit.width("\u001b]8;;https://mutastic.io\u001b\\docs\u001b]8;;\u001b\\"))
        .isEqualTo(4);
  }

  @Test
  void theDumpSaysWhatEachWorkerHolds() {
    var held =
        new Worker(
            "m3c91e0a2",
            "src/testrun.rs:114 spawn() call removed",
            Duration.ofSeconds(164),
            Duration.ofSeconds(40),
            true,
            23);
    var snapshot =
        new Snapshot(
            Phase.MUTANTS,
            "",
            Optional.empty(),
            2871,
            5525,
            COUNTS,
            Duration.ofSeconds(1471),
            HALF,
            List.of(Optional.of(held), Optional.empty()),
            true);

    assertThat(Frames.dump(snapshot, false))
        .containsExactly(
            "running   1 worker at 24m31s",
            "   1 m3c91e0a2  src/testrun.rs:114 spawn() call removed  [00:02:44] SLOW"
                + " (expected 40s)",
            "               covered by 23 tests",
            "   2 idle");
  }

  @Test
  void theDumpSaysWhatEachPhaseIsDoing() {
    assertThat(Frames.dump(building(192), false))
        .containsExactly(
            "running   gradle testClasses at 3m12s", "          last: \"> Task :compileJava\"");
    assertThat(Frames.dump(of(Phase.READING, "the Gradle build", 0, 0, 1), false))
        .containsExactly("running   reading the Gradle build at 1s");
    assertThat(
            Frames.dump(
                of(Phase.CHECKING, "running each catching test on the unmutated code", 18, 52, 38),
                false))
        .containsExactly(
            "running   running each catching test on the unmutated code at 38s · 18 of 52 done");
  }

  @Test
  void eachPhaseEndsWithALine() {
    assertThat(Frames.built(5525, Duration.ofSeconds(581)))
        .isEqualTo("built     5525 mutants in 9m41s\n");
    assertThat(Frames.built(1, Duration.ofMillis(840))).isEqualTo("built     1 mutant in 840ms\n");
    assertThat(Frames.ran(of(Phase.MUTANTS, "", 5525, 5525, 0), Duration.ofSeconds(2052), false))
        .isEqualTo(
            "ran       5525 mutants in 34m12s · ✓ 2610 caught · ✗ 41 missed · ○ 214 not covered"
                + " · ⏱ 6 timed out\n");
    assertThat(Frames.checked(52, 41, Duration.ofSeconds(51)))
        .isEqualTo("checked   52 missed mutants in 51.0s · a test to write for 41\n");
    assertThat(Frames.checked(1, 0, Duration.ofMillis(3500)))
        .isEqualTo("checked   1 missed mutant in 3.5s · no test found to suggest\n");
  }

  @Test
  void durationsClocksAndTimersTakeTheirForms() {
    assertThat(Frames.duration(Duration.ofMillis(840))).isEqualTo("840ms");
    assertThat(Frames.duration(Duration.ofMillis(3500))).isEqualTo("3.5s");
    assertThat(Frames.duration(Duration.ofSeconds(51))).isEqualTo("51.0s");
    assertThat(Frames.duration(Duration.ofSeconds(581))).isEqualTo("9m41s");
    assertThat(Frames.duration(Duration.ofSeconds(3725))).isEqualTo("62m05s");
    assertThat(Frames.clock(Duration.ofMillis(38_900))).isEqualTo("38s");
    assertThat(Frames.clock(Duration.ofSeconds(192))).isEqualTo("3m12s");
    assertThat(Frames.clock(Duration.ofMinutes(62))).isEqualTo("1h02m");
    assertThat(Frames.timer(Duration.ofSeconds(231))).isEqualTo("[00:03:51]");
  }
}
