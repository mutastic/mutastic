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

  @Test
  void anIgnoreSaysWhy() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var run = Samples.mutastic("ignore", ID, project.toString());

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err()).contains("ignore needs a reason");
  }
}
