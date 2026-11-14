package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/// A mutant's `replacement`, written over its `location` in the source as written, gives the line
/// the mutant runs: the source the reports and events give is the mutant's, not words about it, as
/// in the Rust edition. Checked on every mutant of the pricing sample, by its source text.
class MutationSourceTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");

  /// What each of the sample's default mutants makes of its line, written out by hand.
  private static final Map<String, String> MUTATED =
      Map.ofEntries(
          Map.entry("m855c8e3c", "if (total > 100) {"),
          Map.entry("m53e1dc80", "if (false) {"),
          Map.entry("mfb551430", "if (true) {"),
          Map.entry("mabd5e44c", "return total * 10;"),
          Map.entry("mf7bb3882", "return 0;"),
          Map.entry("m3264f639", "return 1;"),
          Map.entry("m2de3396d", "if (total <= freeShippingFrom) {"),
          Map.entry("m73f3386e", "if (false) {"),
          Map.entry("m091729b1", "if (true) {"),
          Map.entry("m39e3e756", "return 0;"),
          Map.entry("m3b5420b6", "return 1;"));

  @TempDir Path work;

  @Test
  void eachReplacementOverItsLocationIsTheMutantsLine() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    var report = work.resolve("mutation.json");

    Samples.mutastic("run", project.toString(), "--operators=stronger", "--report=json:" + report);

    var spliced = spliced(JsonMapper.shared().readTree(report.toFile()));
    assertThat(spliced).containsAllEntriesOf(MUTATED);
    assertThat(spliced).hasSizeGreaterThan(MUTATED.size());
  }

  /// Each mutant's line with its replacement written over its location, by its id. A mutant without
  /// a replacement, or whose replacement leaves its line as it was, fails.
  private static Map<String, String> spliced(JsonNode report) {
    var lines = new HashMap<String, String>();
    for (JsonNode file : report.get("files").values()) {
      var source = List.of(file.get("source").asString().split("\n", -1));
      for (JsonNode mutant : file.get("mutants").values()) {
        var id = mutant.get("id").asString();
        assertThat(mutant.has("replacement")).as(id).isTrue();
        var start = mutant.get("location").get("start");
        var end = mutant.get("location").get("end");
        var first = source.get(start.get("line").asInt() - 1);
        var last = source.get(end.get("line").asInt() - 1);
        var line =
            first.substring(0, start.get("column").asInt() - 1)
                + mutant.get("replacement").asString()
                + last.substring(end.get("column").asInt() - 1);
        assertThat(line).as(id).isNotEqualTo(first);
        lines.put(id, line.strip());
      }
    }
    return lines;
  }
}
