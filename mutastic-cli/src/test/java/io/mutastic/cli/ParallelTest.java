package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.SortedMap;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Workers side by side give the same results as one worker, even when the tests fight over a fixed
/// port: `samples/ports` holds port 47613 while its test runs.
class ParallelTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "ports");
  private static final Path CONTENTION =
      Path.of(System.getProperty("mutastic.samples"), "contention");

  @TempDir Path work;

  @Test
  void aPortClashIsNeverACatch() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("ports"));

    var serial =
        statuses(
            Samples.mutastic("run", project.toString(), "--format=jsonl", "--workers=1").out());
    var parallel = Samples.mutastic("run", project.toString(), "--format=jsonl", "--workers=4");

    assertThat(statuses(parallel.out())).isEqualTo(serial);
    // The boundary nothing checks: a clash counted as a catch would hide it.
    assertThat(serial).containsEntry("`<` becomes `<=`", "Survived");
    assertThat(parallel.out()).doesNotContain("BindException");
  }

  /// A test that waits for a shared resource is slow only side by side: the later copies run out of
  /// time. A timeout then is the contention's, not the mutant's, so it runs again alone and reports
  /// what the mutant does.
  @Test
  void aTimeoutSideBySideRunsAgainAlone() throws Exception {
    var project = Samples.copy(CONTENTION, work.resolve("contention"));

    var parallel = Samples.mutastic("run", project.toString(), "--format=jsonl", "--workers=4");

    var statuses = statuses(parallel.out());
    assertThat(statuses).isNotEmpty().doesNotContainValue("Timeout");
    assertThat(statuses)
        .hasEntrySatisfying(
            statuses.keySet().stream()
                .filter(key -> key.startsWith("`<` becomes `<=`"))
                .findFirst()
                .orElseThrow(),
            status -> assertThat(status).isEqualTo("Survived"));
  }

  /// Each mutant's status by its change, in a stable order.
  private static SortedMap<String, String> statuses(String jsonl) {
    var statuses = new TreeMap<String, String>();
    jsonl
        .lines()
        .filter(line -> line.contains("\"reason\":\"mutant-finished\""))
        .forEach(
            line ->
                statuses.put(
                    line.replaceAll(".*\"description\":\"([^\"]*)\".*", "$1")
                        + " "
                        + line.replaceAll(".*\"id\":\"(m[0-9a-f]+)\".*", "$1"),
                    line.replaceAll(".*\"status\":\"([A-Za-z]+)\".*", "$1")));
    var byChange = new TreeMap<String, String>();
    statuses.forEach(
        (key, status) ->
            byChange.merge(
                key.substring(0, key.lastIndexOf(' ')),
                status,
                (earlier, later) -> earlier + "," + later));
    return byChange;
  }
}
