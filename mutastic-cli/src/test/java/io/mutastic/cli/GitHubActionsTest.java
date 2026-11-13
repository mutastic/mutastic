package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// On GitHub Actions a full run annotates each of its first misses, on stderr so a format's own
/// output is untouched, and adds a summary to the job's page; elsewhere, or with
/// `MUTASTIC_GITHUB=off`, it does neither.
class GitHubActionsTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");

  @TempDir Path work;

  @Test
  void aRunOnGitHubActionsAnnotatesItsMissesAndSummarisesTheJob() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    var summary = work.resolve("summary.md");
    var run =
        Samples.mutastic(
            Map.of("GITHUB_ACTIONS", "true", "GITHUB_STEP_SUMMARY", summary.toString()),
            "run",
            project.toString(),
            "--format=jsonl");

    assertThat(run.err())
        .contains(
            "::warning file=src/main/java/com/example/pricing/Pricing.java,line=17,"
                + "title=mutastic%3A missed m855c8e3c::no test checks `discount()` at"
                + " `total == 100`");
    assertThat(run.out()).doesNotContain("::warning");
    assertThat(Files.readString(summary))
        .startsWith("### mutastic: ")
        .contains("| Caught | Missed | Not covered | Timed out |")
        .contains("| `m855c8e3c` | `src/main/java/com/example/pricing/Pricing.java:17` |");
  }

  @Test
  void elsewhereOrTurnedOffItDoesNeither() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    var summary = work.resolve("summary.md");
    var off =
        Samples.mutastic(
            Map.of(
                "GITHUB_ACTIONS",
                "true",
                "MUTASTIC_GITHUB",
                "off",
                "GITHUB_STEP_SUMMARY",
                summary.toString()),
            "run",
            project.toString(),
            "--format=concise");
    var elsewhere = Samples.mutastic("run", project.toString(), "--format=concise");

    assertThat(off.err()).doesNotContain("::warning");
    assertThat(elsewhere.err()).doesNotContain("::warning");
    assertThat(summary).doesNotExist();
  }
}
