package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Tests whose catches aren't the mutants' doing, found when each test that caught one runs again
/// on the unmutated code after the mutants. In the `false-catches` sample, `FeesTest` reads a
/// percent only `ConfigurationTest` sets, so it fails whenever it runs on its own; and
/// `LedgerTest`'s first two tests share a file in the build directory, which the second leaves an
/// entry in, so once the suite has run they both fail. Each passes when the whole suite runs, as it
/// does before any mutant, and fails against every mutant it reaches. Taken at face value they
/// caught 19 of 21 mutants, a pass at 80.
class FalseCatchesTest {
  private static final Path SAMPLE =
      Path.of(System.getProperty("mutastic.samples"), "false-catches");

  private static final String FEES = "com.example.ledger.FeesTest#chargesTheConfiguredPercent";

  /// `fee()`'s `*` becoming `/`: caught only by `FeesTest`'s broken test, and missed by the one
  /// that only checks the fee isn't negative.
  private static final String FEE_MUTANT = "m886b61b4";

  @TempDir Path work;

  @Test
  void theRunDoesntPassAndSaysWhichCatchesWereFalse() throws Exception {
    var run = run("--format=agent");

    assertThat(run.exit()).isEqualTo(Main.TESTS_FAIL);
    assertThat(run.out().lines().findFirst())
        .hasValue(
            "mutastic: FAIL (a test that caught mutants fails on its own, with no mutant in) ·"
                + " score 57.1% (threshold 80; 75.0% where tests reach) · 4 missed, 5 not covered,"
                + " 12 caught of 21 mutants");
    assertThat(run.out())
        .contains(
            "flaky: FeesTest#chargesTheConfiguredPercent passes among the others but fails on its"
                + " own, with no mutant in (run again after the mutants, it failed with"
                + " AssertionFailedError): it counts on something another test or the run sets up"
                + " or leaves behind.",
            "flaky: LedgerTest#aNewLedgerIsEmpty passes among the others but fails on its own",
            "flaky: LedgerTest#recordingAddsToTheTotal passes among the others but fails on its"
                + " own",
            "or run without it with `--exclude-tests=FeesTest`",
            "covered by: FeesTest#aFeeIsNeverNegative; all still pass with the change"
                + " (FeesTest#chargesTheConfiguredPercent failed against it, but fails on its own"
                + " with no mutant in too: that catch doesn't count)",
            "no test reaches these lines, or only a flaky one does");
  }

  /// What the cards claim, checked against the run itself: the test did catch the mutant first, the
  /// mutant then ran again without it, and what the other covering tests made of it is what the
  /// card says.
  @Test
  void theEventsSayWhatEachCatchBecame() throws Exception {
    var run = run("--format=jsonl");

    var lines = run.out().lines().toList();
    assertThat(eventFor(lines, "mutant-finished", FEE_MUTANT))
        .contains("\"status\":\"Killed\"", "\"caughtBy\":\"" + FEES + "\"");
    assertThat(eventFor(lines, "mutant-revised", FEE_MUTANT))
        .contains(
            "\"status\":\"Survived\"",
            "\"coveredBy\":[\"com.example.ledger.FeesTest#aFeeIsNeverNegative\"]",
            "\"falseCatchBy\":\"" + FEES + "\"");
    assertThat(lines.getLast())
        .contains(
            "\"reason\":\"run-finished\"",
            "\"killed\":12",
            "\"passed\":false",
            "{\"test\":\"" + FEES + "\",\"failure\":[\"org.opentest4j.AssertionFailedError\"]",
            "\"failsAlone\":true");
    assertThat(run.exit()).isEqualTo(Main.TESTS_FAIL);
  }

  @Test
  void peopleAreToldWhyItFailed() throws Exception {
    var run = run("--format=full");

    assertThat(run.out())
        .contains(
            "! flaky: FeesTest#chargesTheConfiguredPercent passes among the others but fails on its"
                + " own, with no mutant in (run again after the mutants, it failed with"
                + " AssertionFailedError); its catches don't count, the mutants it reached ran"
                + " again without it, and the run can't pass until it passes on its own",
            "[FAIL] A test that caught mutants fails on its own, with no mutant in, so its catches"
                + " don't count: see the flaky tests above.");
    assertThat(run.exit()).isEqualTo(Main.TESTS_FAIL);
  }

  /// `verify` checks its catches too: a mutant only a broken test caught isn't proved caught.
  @Test
  void verifyDoesntPassOnAFalseCatch() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("false-catches"));

    var verify = Samples.mutastic("verify", FEE_MUTANT, project.toString());

    assertThat(verify.out())
        .startsWith("mutastic: m886b61b4 is still missed")
        .contains(
            "flaky: FeesTest#chargesTheConfiguredPercent passes among the others but fails on its"
                + " own");
    assertThat(verify.exit()).isEqualTo(Main.TESTS_FAIL);
  }

  /// The gate checks its catches too: a change whose mutants only a broken test catches isn't done.
  @Test
  void theGateDoesntPassOnAFalseCatch() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("false-catches"));
    Files.writeString(project.resolve(".gitignore"), "build/\n.gradle/\n");
    inGit(project, "init", "-q", "-b", "main");
    inGit(project, "add", "-A");
    inGit(
        project,
        "-c",
        "user.name=t",
        "-c",
        "user.email=t@t",
        "-c",
        "commit.gpgsign=false",
        "commit",
        "-qm",
        "start");
    Samples.mutastic("gate", "open", project.toString());
    var fees = project.resolve("src/main/java/com/example/ledger/Fees.java");
    Files.writeString(
        fees,
        Files.readString(fees)
            .replace("return amount * percent / 100;", "return percent * amount / 100;"));

    var check = Samples.mutastic("gate", "check", project.toString());

    assertThat(check.out())
        .startsWith(
            "mutastic gate: a test that caught mutants on the lines the task changed fails on its"
                + " own, with no mutant in, so its catches don't count and the task isn't done.")
        .contains("flaky: FeesTest#chargesTheConfiguredPercent passes among the others");
    assertThat(check.exit()).isEqualTo(Main.FAILED);
  }

  /// Runs the version-control command `args` in `project`, as `GateTest` does.
  private static void inGit(Path project, String... args) throws Exception {
    var command = new java.util.ArrayList<>(java.util.List.of("git"));
    command.addAll(java.util.List.of(args));
    int exit =
        new ProcessBuilder(command).directory(project.toFile()).inheritIO().start().waitFor();
    assertThat(exit).as("%s", String.join(" ", args)).isZero();
  }

  /// The way out for a test that can't yet run on its own: leave it out, and the run passes or
  /// fails on the rest.
  @Test
  void leavingTheTestsOutJudgesTheRunOnTheRest() throws Exception {
    var run = run("--format=agent", "--exclude-tests=FeesTest", "--exclude-tests=LedgerTest");

    assertThat(run.out()).doesNotContain("flaky").startsWith("mutastic: FAIL · score");
    assertThat(run.exit()).isEqualTo(Main.FAILED);
  }

  private Samples.Result run(String... options) throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("false-catches"));
    var args =
        new java.util.ArrayList<>(java.util.List.of("run", project.toString(), "--workers=1"));
    args.addAll(java.util.List.of(options));
    return Samples.mutastic(args.toArray(String[]::new));
  }

  private static String eventFor(java.util.List<String> lines, String reason, String id) {
    return lines.stream()
        .filter(line -> line.contains("\"reason\":\"" + reason + "\",\"id\":\"" + id + "\""))
        .findFirst()
        .orElseThrow();
  }
}
