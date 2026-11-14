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
  private static final String ARGUED =
      "Differs only when total is 100. That cannot happen because prices end in 9.";

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

  /// A reviewed decision on a mutant of another set is left alone by a run or a prune under this
  /// one, and goes as any other does once its line changes.
  @Test
  void pruningUnderAnotherSetKeepsAnIgnoreUntilItsLineChanges() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    var ignores = project.resolve(".mutastic/ignores.yaml");

    var run =
        Samples.mutastic(
            "run",
            project.toString(),
            "--operators=mutastic/other-comparison",
            "--format=jsonl",
            "--report=none");
    var stronger = firstMatch("\"id\":\"(?<found>m[0-9a-f]+)\"", run.out());
    Samples.mutastic("ignore", stronger, project.toString(), "--reason=reviewed");
    var underDefaults =
        Samples.mutastic("run", project.toString(), "--format=jsonl", "--report=none");
    var kept = Samples.mutastic("ignore", "--prune", project.toString());
    var keptFile = Files.readString(ignores);
    var line = firstMatch("code: '(?<found>.*)'", keptFile);
    var pricing = project.resolve("src/main/java/com/example/pricing/Pricing.java");
    Files.writeString(pricing, Files.readString(pricing).replace(line, line + " // changed"));
    var gone = Samples.mutastic("ignore", "--prune", project.toString());

    assertThat(underDefaults.out()).contains("\"staleIgnores\":[]");
    assertThat(kept.out()).contains("every ignore still holds");
    assertThat(keptFile).contains("- id: " + stronger);
    assertThat(gone.out()).contains("removed 1 ignore that no longer hold: " + stronger);
  }

  private static String firstMatch(String regex, String text) {
    return java.util.regex.Pattern.compile(regex)
        .matcher(text)
        .results()
        .map(found -> found.group("found"))
        .findFirst()
        .orElseThrow();
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
            "--reason=" + ARGUED);
    var stillMissed =
        Samples.mutastic(
            "run", project.toString(), "--only=" + ID, "--format=jsonl", "--report=none");
    var accepted = Samples.mutastic("ignore", "--accept", ID, project.toString());
    var nowIgnored =
        Samples.mutastic(
            "run", project.toString(), "--only=" + ID, "--format=jsonl", "--report=none");

    assertThat(proposed.exit()).isEqualTo(Main.PASSED);
    assertThat(proposed.out())
        .contains(ID + " is proposed for ignoring: " + ARGUED)
        .contains("it counts as missed until a person accepts it")
        .contains("to accept: mutastic ignore --accept " + ID);
    assertThat(stillMissed.out()).contains("\"status\":\"Survived\"");
    assertThat(accepted.out()).contains(ID + " is ignored, as proposed: " + ARGUED);
    assertThat(Files.readString(project.resolve(".mutastic/ignores.yaml")))
        .contains("- id: " + ID, "reason: '" + ARGUED + "'", "kind: unreachable");
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
    var nothingToRemove = Samples.mutastic("ignore", "--remove", ID, project.toString());

    assertThat(withdrawn.out())
        .contains(ID + " is no longer proposed")
        .contains("to undo: mutastic ignore " + ID + " --propose '--reason=never 100'");
    assertThat(project.resolve(".mutastic/proposals.yaml")).doesNotExist();
    assertThat(nothing.exit()).isEqualTo(Main.FAILED);
    assertThat(nothing.out()).isEmpty();
    assertThat(nothing.err()).startsWith("mutastic: " + ID + " isn't proposed");
    assertThat(nothingToRemove.exit()).isEqualTo(Main.FAILED);
    assertThat(nothingToRemove.out()).isEmpty();
    assertThat(nothingToRemove.err()).contains(ID + " isn't ignored");
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
    assertThat(unknown.err()).contains("--as takes equivalent, unreachable, telemetry,");
  }

  @Test
  void anIgnoreSaysWhy() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var run = Samples.mutastic("ignore", ID, project.toString());

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err()).contains("ignore needs a reason");
  }
}
