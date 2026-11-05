package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `mutastic ignore`: a decision not to test a mutant, with its reason and the line it was made on,
/// in `.mutastic/ignores.yaml`. It holds until that line changes; then the mutant runs again, the
/// run says so, and `--prune` removes it.
class IgnoreTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final String ID = "m855c8e3c";

  @TempDir Path work;

  @Test
  void anIgnoreHoldsUntilItsLineChanges() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var ignored =
        Samples.mutastic("ignore", ID, project.toString(), "--reason=a hundred is checked by hand");
    var recorded = Files.readString(project.resolve(".mutastic/ignores.yaml"));
    var skipped = Samples.mutastic("run", project.toString(), "--format=jsonl", "--report=none");
    var pricing = project.resolve("src/main/java/com/example/pricing/Pricing.java");
    Files.writeString(
        pricing,
        Files.readString(pricing).replace("if (total >= 100) {", "if (total >= 100) { // 10% off"));
    var stale = Samples.mutastic("run", project.toString(), "--format=jsonl", "--report=none");
    var pruned = Samples.mutastic("ignore", "--prune", project.toString());

    assertThat(ignored.exit()).isEqualTo(Main.PASSED);
    assertThat(recorded)
        .contains(
            "- id: " + ID, "code: 'if (total >= 100) {'", "reason: 'a hundred is checked by hand'");
    assertThat(skipped.out()).contains("user: a hundred is checked by hand (ignored)");
    assertThat(stale.out())
        .doesNotContain("(ignored)")
        .contains("\"staleIgnores\":[\"" + ID + "\"]");
    assertThat(pruned.out()).contains("removed 1 ignore that no longer hold: " + ID);
    assertThat(Files.readString(project.resolve(".mutastic/ignores.yaml"))).doesNotContain(ID);
  }

  /// A proposal waits for a person: its mutant still counts as missed until it's accepted, and then
  /// it's an ignore like any other, with its reason and sort as proposed.
  @Test
  void aProposedIgnoreCountsAsMissedUntilAPersonAcceptsIt() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    var proposals = project.resolve(".mutastic/proposals.yaml");

    var proposed =
        Samples.mutastic(
            "ignore",
            ID,
            project.toString(),
            "--propose",
            "--as=unreachable",
            "--reason=never 100");
    var stillMissed =
        Samples.mutastic(
            "run", project.toString(), "--only=" + ID, "--format=jsonl", "--report=none");
    var accepted = Samples.mutastic("ignore", "--accept", ID, project.toString());
    var nowIgnored =
        Samples.mutastic(
            "run", project.toString(), "--only=" + ID, "--format=jsonl", "--report=none");

    assertThat(proposed.exit()).isEqualTo(Main.PASSED);
    assertThat(proposed.out())
        .contains(ID + " is proposed for ignoring: never 100")
        .contains("it counts as missed until a person accepts it")
        .contains("to accept: mutastic ignore --accept " + ID);
    assertThat(stillMissed.out()).contains("\"status\":\"Survived\"");
    assertThat(accepted.out()).contains(ID + " is ignored, as proposed: never 100");
    assertThat(Files.readString(project.resolve(".mutastic/ignores.yaml")))
        .contains("- id: " + ID, "reason: 'never 100'", "kind: unreachable");
    assertThat(proposals).doesNotExist(); // none left waiting
    assertThat(nowIgnored.out()).contains("\"status\":\"Ignored\"");
  }

  /// A proposal can be withdrawn; accepting one that isn't there fails, and says so.
  @Test
  void aProposalCanBeWithdrawn() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    Samples.mutastic("ignore", ID, project.toString(), "--propose", "--reason=never 100");
    var withdrawn = Samples.mutastic("ignore", "--remove", ID, project.toString());
    var nothing = Samples.mutastic("ignore", "--accept", ID, project.toString());

    assertThat(withdrawn.out())
        .contains(ID + " is no longer proposed")
        .contains("to undo: mutastic ignore " + ID + " --propose '--reason=never 100'");
    assertThat(project.resolve(".mutastic/proposals.yaml")).doesNotExist();
    assertThat(nothing.exit()).isEqualTo(Main.FAILED);
    assertThat(nothing.out()).contains(ID + " isn't proposed");
  }

  /// Each says where it wrote, and how to undo it.
  @Test
  void anIgnoreAndItsRemovalEachSayHowToUndoThem() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    var file = project.resolve(".mutastic/ignores.yaml");

    var ignored = Samples.mutastic("ignore", ID, project.toString(), "--reason=checked by hand");
    var removed = Samples.mutastic("ignore", "--remove", ID, project.toString());

    assertThat(ignored.out())
        .contains("recorded in " + file)
        .contains("to undo: mutastic ignore --remove " + ID + " " + project);
    assertThat(removed.out())
        .contains(ID + " is no longer ignored")
        .contains("removed from " + file)
        .contains("to undo: mutastic ignore " + ID + " '--reason=checked by hand' " + project);
    assertThat(Files.readString(file)).doesNotContain(ID);
  }

  /// `--as` records the sort of decision, the summary counts ignores by it, and undoing a removal
  /// keeps it.
  @Test
  void anIgnoreCanSayWhatSortOfDecisionItIs() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var ignored =
        Samples.mutastic(
            "ignore", ID, project.toString(), "--as=unreachable", "--reason=totals come in tens");
    var recorded = Files.readString(project.resolve(".mutastic/ignores.yaml"));
    var run = Samples.mutastic("run", project.toString(), "--format=concise", "--report=none");
    var removed = Samples.mutastic("ignore", "--remove", ID, project.toString());
    var unknown = Samples.mutastic("ignore", ID, project.toString(), "--as=boring", "--reason=x");

    assertThat(ignored.exit()).isEqualTo(Main.PASSED);
    assertThat(recorded).contains("reason: 'totals come in tens'", "kind: unreachable");
    assertThat(run.out()).contains("1 ignored as unreachable");
    assertThat(removed.out()).contains("'--reason=totals come in tens' --as=unreachable");
    assertThat(unknown.exit()).isEqualTo(Main.USAGE);
    assertThat(unknown.err()).contains("unknown as boring (one of equivalent, unreachable");
  }

  @Test
  void anIgnoreSaysWhy() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var run = Samples.mutastic("ignore", ID, project.toString());

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err()).contains("ignore needs a reason");
  }
}
