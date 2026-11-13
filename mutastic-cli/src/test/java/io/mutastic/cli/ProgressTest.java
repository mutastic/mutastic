package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.mutastic.api.BuildTool;
import io.mutastic.api.BuiltIn;
import io.mutastic.api.CoveragePass;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Status;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// What a run says on stderr as its phases go by, with the clock moved by hand and each tick called
/// as the ticker would.
class ProgressTest {
  private final AtomicLong now = new AtomicLong();
  private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

  @TempDir Path work;

  private Progress progress(Progress.Mode mode) {
    var settings = new Progress.Settings(false, Duration.ofSeconds(30), now::get, false);
    return new Progress(new PrintStream(bytes, true, StandardCharsets.UTF_8), mode, settings);
  }

  private String err() {
    return bytes.toString(StandardCharsets.UTF_8);
  }

  private void after(Duration duration) {
    now.addAndGet(duration.toNanos());
  }

  /// A mutant in its own method, `method`, with this result.
  private static MutantResult result(String method, Status status) {
    var mutant =
        new Mutant(
            "com/example/A",
            method,
            "()Z",
            0,
            1,
            BuiltIn.BOUNDARY,
            "IF_ICMPLT",
            "IF_ICMPLE",
            Optional.empty());
    return new MutantResult(
        mutant,
        status,
        List.of(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        1,
        Optional.empty(),
        Map.of(),
        Optional.empty(),
        Duration.ZERO);
  }

  @Test
  void offATerminalEachPhaseEndsWithALineAndASilenceGetsAHeartbeat() throws IOException {
    var progress = progress(Progress.Mode.COUNTER);
    var log = Files.writeString(work.resolve("gradle.log"), "> Task :compileJava\n");

    progress.started("1.1.0", "Gradle");
    progress.building(Optional.of(new BuildTool.Building("gradle testClasses", log)));
    after(Duration.ofSeconds(29));
    progress.tick();
    after(Duration.ofSeconds(1));
    progress.tick();
    progress.findingMutants();
    after(Duration.ofSeconds(2));
    progress.mutantsFound(2);
    progress.coverageProgress(0, 3);
    after(Duration.ofSeconds(30));
    progress.tick();
    progress.coverageFinished(new CoveragePass(17, 3, Duration.ofMillis(789), 2, List.of()));
    progress.mutantFinished(result("m1", Status.CAUGHT));
    progress.mutantFinished(result("m2", Status.MISSED));
    after(Duration.ofSeconds(4));
    progress.checking("running each catching test on the unmutated code", 0, 1);
    progress.finished();

    assertThat(err())
        .isEqualTo(
            """
            mutastic 1.1.0 · reading the Gradle build…
            building  gradle testClasses · 30s
            built     2 mutants in 32.0s
            coverage  [0/3 tests] · 30s
            coverage  3 tests on JDK 17 in 789ms · 2 mutants
            ran       2 mutants in 4.0s · ✓ 1 caught · ✗ 1 missed · ○ 0 not covered · ⏱ 0 timed out
            checked   1 missed mutant in 0ms · no test found to suggest
            """);
  }

  @Test
  void aRevisedResultCountsInsteadOfItsFirst() {
    var progress = progress(Progress.Mode.COUNTER);
    progress.started("1.1.0", "Maven");
    progress.mutantsFound(1);
    progress.coverageFinished(new CoveragePass(17, 1, Duration.ofMillis(5), 1, List.of()));
    progress.mutantFinished(result("m1", Status.CAUGHT));
    progress.checking("step", 0, 0);
    progress.mutantRevised(result("m1", Status.MISSED));
    progress.finished();

    assertThat(err()).contains("checked   1 missed mutant in");
  }

  @Test
  void withNothingMissedNothingIsCheckedAndOnlyThePhasesThatEndedSaySo() {
    var progress = progress(Progress.Mode.COUNTER);
    progress.started("1.1.0", "Maven");
    progress.mutantsFound(1);
    progress.coverageFinished(new CoveragePass(17, 1, Duration.ofMillis(5), 1, List.of()));
    progress.mutantFinished(result("m1", Status.CAUGHT));
    progress.finished();

    assertThat(err().lines().map(line -> line.substring(0, 10)))
        .containsExactly("mutastic 1", "built     ", "coverage  ", "ran       ");
  }

  @Test
  void onATerminalTheLiveLineIsRedrawnInPlaceAndClearedForEachPermanentLine() {
    var progress = progress(Progress.Mode.BAR);

    progress.started("1.1.0", "Gradle");
    progress.tick();
    after(Duration.ofSeconds(1));
    progress.tick();
    progress.note("a note");
    progress.finished();

    assertThat(err())
        .isEqualTo(
            "mutastic 1.1.0 · reading the Gradle build…\n"
                + "⠋ reading   the Gradle build · 0s"
                + "\r\u001b[2K⠙ reading   the Gradle build · 1s"
                + "\r\u001b[2Ka note\n"
                + "⠹ reading   the Gradle build · 1s"
                + "\r\u001b[2K");
  }

  @Test
  void aTwoLineBlockIsClearedWhole() throws IOException {
    var progress = progress(Progress.Mode.BAR);
    var log = Files.writeString(work.resolve("maven.log"), "[INFO] Building pricing\n");
    progress.started("1.1.0", "Maven");
    progress.building(Optional.of(new BuildTool.Building("mvn test-compile", log)));
    progress.tick();
    progress.close();

    assertThat(err())
        .endsWith(
            "⠋ building  mvn test-compile · 0s\n   └ [INFO] Building pricing"
                + "\r\u001b[2K\u001b[1A\u001b[2K");
  }

  @Test
  void noneSaysNothing() {
    var progress = progress(Progress.Mode.NONE);
    progress.started("1.1.0", "Gradle");
    after(Duration.ofMinutes(5));
    progress.tick();
    progress.finished();

    assertThat(err()).isEmpty();
  }

  @Test
  void theHeartbeatComesFromTheEnvironment() {
    assertThat(Progress.Settings.of(Map.of(), false).heartbeat()).isEqualTo(Progress.HEARTBEAT);
    assertThat(Progress.Settings.of(Map.of("MUTASTIC_HEARTBEAT", "1"), false).heartbeat())
        .isEqualTo(Duration.ofSeconds(1));
    assertThat(Progress.Settings.of(Map.of("MUTASTIC_HEARTBEAT", "0.5"), false).heartbeat())
        .isEqualTo(Duration.ofMillis(500));
    assertThatThrownBy(() -> Progress.Settings.of(Map.of("MUTASTIC_HEARTBEAT", "soon"), false))
        .hasMessageContaining("MUTASTIC_HEARTBEAT");
  }
}
