package io.mutastic.cli;

import static io.mutastic.cli.Samples.mutastic;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `--kill-matrix`: every covering test that catches a mutant, not only the first, so a replay can
/// tell which mutants one test catches together. The first is the one `caughtBy` names.
class KillMatrixTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "ledger");

  /// `"caughtBy":"…",…"caughtByAll":["…",…]`: the first catcher, then all of them.
  private static final Pattern CAUGHT =
      Pattern.compile("\"caughtBy\":\"(?<first>[^\"]+)\".*?\"caughtByAll\":\\[(?<all>[^\\]]+)\\]");

  @TempDir Path work;

  @Test
  void eachCatchNamesEveryTestThatCatchesIt() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("ledger"));

    var run = mutastic("run", project.toString(), "--kill-matrix", "--format=jsonl");

    var caught = run.out().lines().filter(line -> line.contains("\"status\":\"Killed\"")).toList();
    assertThat(caught).isNotEmpty();
    int several = 0;
    for (var line : caught) {
      var found = CAUGHT.matcher(line);
      assertThat(found.find()).as(line).isTrue();
      assertThat(found.group("all")).startsWith("\"" + found.group("first") + "\"");
      several += found.group("all").contains("\",\"") ? 1 : 0;
    }
    assertThat(several).as("mutants more than one test catches").isPositive();
  }
}
