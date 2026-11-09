package io.mutastic.cli;

import static io.mutastic.cli.Samples.mutastic;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `--shard`: a run split into shares, as the Nightly splits its longest, runs every mutant in
/// exactly one share, and the shares' results together are the whole run's.
class ShardTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "ledger");

  private static final Pattern RESULT =
      Pattern.compile(
          "\"reason\":\"mutant-finished\",\"id\":\"(?<id>[^\"]+)\".*?\"status\":\"(?<status>[^\"]+)\"");

  @TempDir Path work;

  @Test
  void theSharesTogetherAreTheWholeRun() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("ledger")).toString();

    var whole = results(mutastic("run", project, "--format=jsonl", "--threshold=0"));
    var first = results(mutastic("run", project, "--shard=1/2", "--format=jsonl", "--threshold=0"));
    var second =
        results(mutastic("run", project, "--shard=2/2", "--format=jsonl", "--threshold=0"));

    assertThat(first).isNotEmpty();
    assertThat(second).isNotEmpty();
    assertThat(first.keySet()).doesNotContainAnyElementsOf(second.keySet());
    var together = new HashMap<>(first);
    together.putAll(second);
    assertThat(together).isEqualTo(whole);
  }

  private static Map<String, String> results(Samples.Result run) {
    assertThat(run.exit()).as(run.err()).isZero();
    var found = new HashMap<String, String>();
    var matcher = RESULT.matcher(run.out());
    while (matcher.find()) {
      found.put(matcher.group("id"), matcher.group("status"));
    }
    return found;
  }
}
