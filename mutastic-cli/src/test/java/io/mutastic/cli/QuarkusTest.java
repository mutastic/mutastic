package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `samples/quarkus`: `@QuarkusTest` tests, which run the app in Quarkus's own class loader, so
/// mutants reach Quarkus's copies of the classes by redefinition. With the docs' Mutiny rules file
/// as a plugin, its operators find the selections and the recovery no test needs.
class QuarkusTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "quarkus");
  private static final Path MUTINY =
      Path.of(System.getProperty("mutastic.docs")).resolve("../../data/plugins/mutiny.yaml");

  @TempDir Path work;

  @Test
  void mutantsReachTheAppQuarkusLoadsAndTheMutinyRulesRun() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("quarkus"));
    Files.copy(MUTINY.normalize(), project.resolve("mutiny.yaml"));
    Files.writeString(project.resolve("mutastic.yaml"), "plugins:\n  - mutiny.yaml\n");

    var run = Samples.mutastic("run", project.toString(), "--format=agent", "--report=none");

    assertThat(run.err()).doesNotContain("mutastic: internal error");
    // Once, under load, every mutant here was caught: if it happens again, say what the workers
    // saw, not only the summary.
    assertThat(run.out())
        .as("the run:%n%s%nits workers:%n%s", run.out(), workerLogs(project))
        .contains("5 missed, 0 not covered, 11 caught of 16 mutants")
        .contains("no test has duplicate items for `.select().distinct()` in top() to remove")
        .contains("no test has more items than `.select().first(…)` in top() lets through")
        .contains(
            "no test makes scoreOrZero() fail, so `.onFailure().recoverWithItem(…)` is never needed")
        .contains("no test checks `isHigh()` at `score == 90`");
  }

  /// Every worker's log, the last lines of each.
  private static String workerLogs(Path project) throws java.io.IOException {
    var logs = new StringBuilder();
    try (var files = Files.list(project.resolve("build/mutastic"))) {
      for (var log :
          files
              .filter(file -> file.getFileName().toString().startsWith("worker"))
              .sorted()
              .toList()) {
        var lines = Files.readAllLines(log);
        logs.append("== ").append(log.getFileName()).append('\n');
        lines
            .subList(Math.max(0, lines.size() - 80), lines.size())
            .forEach(line -> logs.append(line).append('\n'));
      }
    }
    return logs.toString();
  }
}
