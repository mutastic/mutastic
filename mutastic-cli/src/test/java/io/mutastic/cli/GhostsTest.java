package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Ghosts on a family of beans (`samples/ledger`), with `--experimental=ghosts`: where a write
/// doesn't fire the property change its peers do, the peers' call is inserted and the tests run
/// against it. One run, shared. Every claim a ghost makes is the run's own: "still passes" is the
/// ghost missed, "fails" is the test that caught it.
class GhostsTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "ledger");

  @TempDir static Path work;

  private static String out;
  private static String finished;

  @BeforeAll
  static void runTheSample() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("ledger"));
    out =
        Samples.mutastic(
                "run",
                project.toString(),
                "--experimental=ghosts",
                "--format=jsonl",
                "--report=none")
            .out();
    finished =
        out.lines()
            .filter(line -> line.contains("\"reason\":\"run-finished\""))
            .findFirst()
            .orElseThrow();
  }

  @Test
  void aWriteItsPeersAnnounceIsAGhostNoTestNotices() {
    assertThat(finished)
        .containsPattern(
            "\"status\":\"passedUnseen\",[^}]*\"method\":\"applyInterest\",\"field\":\"balance\","
                + "\"inserted\":\"firePropertyChange\\(\\\\\"balance\\\\\"\\)\",\"rule\":\"sameField\","
                + "\"writes\":5")
        .contains("3 of 5 writes to `balance` fire a property change")
        .contains("Account.applyInterest() writes `balance` without telling listeners");
  }

  @Test
  void aTestThatExpectsSilenceMakesTheGhostAppear() {
    assertThat(finished)
        .containsPattern(
            "\"status\":\"appeared\",[^}]*\"method\":\"restore\",(?:(?!\"id\").)*"
                + "\"caughtBy\":\"com.example.ledger.AccountTest#restoringASnapshotIsQuiet\"");
  }

  @Test
  void aSetterItsSiblingsDontShareIsAGhost() {
    assertThat(finished)
        .containsPattern(
            "\"method\":\"setAddress\",\"field\":\"address\",[^}]*\"rule\":\"siblingFields\"")
        .containsPattern(
            "\"method\":\"setOpeningHour\",\"field\":\"openingHour\",[^}]*\"rule\":\"siblingFields\"");
  }

  /// What everything changes at once, a constructor, and a write a helper announces aren't ghosts.
  @Test
  void whatIsQuietOnPurposeIsNoGhost() {
    for (var method : List.of("close", "<init>", "setStatus", "setOwner", "setNickname")) {
      assertThat(finished).doesNotContain("\"method\":\"" + method + "\",\"field\"");
    }
  }

  /// A ghost is never a mutant: it isn't among the run's mutants, its count or its score.
  @Test
  void ghostsAreNotMutants() {
    long mutants =
        out.lines().filter(line -> line.contains("\"reason\":\"mutant-finished\"")).count();

    assertThat(out).doesNotContain("\"operator\":\"ghost/");
    assertThat(finished).contains("\"mutants\":" + mutants + ",");
  }
}
