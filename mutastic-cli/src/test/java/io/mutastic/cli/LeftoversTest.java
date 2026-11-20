package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A file one mutant's tests leave in the temporary directory isn't there for the next mutant's. In
/// `samples/leftovers`, `stash()` and `measure()` each write `shared.txt` in `java.io.tmpdir` and
/// delete it. With `stash()`'s delete removed the file stays; `measure()`'s test, on its write
/// removed, would then read it and pass. One worker runs both, the delete's mutant first.
class LeftoversTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "leftovers");

  @TempDir Path work;

  @Test
  void aFileOneMutantLeavesIsntThereForTheNext() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("leftovers"));

    // Only the removals: any other mutant of `measure()` runs it whole, and its delete would tidy
    // up before the write's mutant runs.
    var run =
        Samples.mutastic(
            "run",
            project.toString(),
            "--format=jsonl",
            "--workers=1",
            "--operators=mutastic/remove-void-call,mutastic/remove-discarded-call");

    var finished = run.out().lines().filter(line -> line.contains("mutant-finished")).toList();
    assertThat(removal(finished, "stash", "mutastic/remove-void-call"))
        .contains("\"status\":\"Survived\"");
    assertThat(removal(finished, "measure", "mutastic/remove-discarded-call"))
        .contains("\"status\":\"Killed\"");
  }

  private static String removal(List<String> finished, String method, String operator) {
    return finished.stream()
        .filter(
            line ->
                line.contains("\"method\":\"" + method + "(")
                    && line.contains("\"operator\":\"" + operator + "\""))
        .findFirst()
        .orElseThrow(
            () -> new AssertionError("no " + operator + " in " + method + ": " + finished));
  }
}
