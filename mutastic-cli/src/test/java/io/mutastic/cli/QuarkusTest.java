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
    assertThat(run.out())
        .contains("5 missed, 0 not covered, 9 caught of 14 mutants")
        .contains("no test has duplicate items for `.select().distinct()` in top() to remove")
        .contains("no test has more items than `.select().first(…)` in top() lets through")
        .contains(
            "no test makes scoreOrZero() fail, so `.onFailure().recoverWithItem(…)` is never needed")
        .contains("no test checks `isHigh()` at `score == 90`");
  }
}
