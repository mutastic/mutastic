package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Tests that keep state in statics, in the `static-state` sample: one run of the build runs each
/// once from clean statics, so none can fail there, and each mutant here is reported as that one
/// run says. Each of `Shop`'s methods is reached by one such test alone, but `both()`, which
/// `BothTest` reaches too.
///
/// `SharedTest` is named for a JDK service, so the worker shares it between runs by its own name,
/// and its count went on from one mutant's run to the next: every mutant it reached looked caught,
/// two of them falsely (`>` becomes `>=`, and the condition always true: it only tries 200). A
/// catch by a test whose run can see such state now runs again in a fresh loader before it counts,
/// and under `--kill-matrix` so does each catcher after the first.
class StaleStateTest {
  private static final Path SAMPLE =
      Path.of(System.getProperty("mutastic.samples"), "static-state");

  @TempDir static Path work;

  private static List<String> mutants;

  @BeforeAll
  static void runTheSample() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("static-state"));
    var run =
        Samples.mutastic(
            "run", project.toString(), "--workers=1", "--kill-matrix", "--format=jsonl");
    mutants =
        run.out().lines().filter(line -> line.contains("\"reason\":\"mutant-finished\"")).toList();
  }

  @Test
  void aMutantOnlyAStatefulTestReachesIsReportedAsOneRunOfTheBuildSays() {
    for (var method :
        List.of("counted", "remembered", "collected", "registered", "held", "nested", "shared")) {
      assertThat(mutant(method, "`price > 100` is always true"))
          .contains("\"status\":\"Survived\"");
      assertThat(mutant(method, "`>` becomes `>=`")).contains("\"status\":\"Survived\"");
    }
    assertThat(mutant("shared", "`-` becomes `+`")).contains("\"status\":\"Killed\"");
  }

  @Test
  void aStaleCatcherIsntAmongEveryCatcher() {
    assertThat(mutant("both", "`>` becomes `>=`"))
        .contains("\"caughtByAll\":[\"demo.BothTest#aHundredIsFullPrice\"]");
    assertThat(mutant("both", "`-` becomes `+`"))
        .contains(
            "\"caughtByAll\":[\"demo.BothTest#aHundredIsFullPrice\",\"demo.SharedTest#runsOnce\"]");
  }

  @Test
  void noTestIsNamedLikelyFlaky() {
    assertThat(mutants).noneMatch(line -> line.contains("unconfirmedCatchBy"));
  }

  /// The one mutant of `Shop.<method>` with `description`.
  private static String mutant(String method, String description) {
    var found =
        mutants.stream()
            .filter(line -> line.contains("\"method\":\"" + method + "(I)I\""))
            .filter(line -> line.contains("\"description\":\"" + description + "\""))
            .toList();
    assertThat(found).as(method + ": " + description).hasSize(1);
    return found.getFirst();
  }
}
