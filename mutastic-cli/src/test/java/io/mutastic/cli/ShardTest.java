package io.mutastic.cli;

import static io.mutastic.cli.Samples.mutastic;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/// `--shard` and `--from-shards`: a run split into shares, as the Nightly splits its longest, runs
/// every mutant in exactly one share, and the shares put back together are the whole run: its
/// results, report, threshold and record. Shares that aren't one whole run's are refused.
class ShardTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final JsonMapper JSON = JsonMapper.builder().build();

  @TempDir static Path work;

  private static String project;
  private static Path shares;
  private static Map<String, JsonNode> whole;
  private static JsonNode wholeFinished;
  private static JsonNode wholeReport;

  /// One whole run of the sample, then its two shares, one shown as JSON Lines and one as people
  /// see it: a share keeps its events whatever the format.
  @BeforeAll
  static void aWholeRunAndItsShares() throws IOException {
    project = Samples.copy(SAMPLE, work.resolve("pricing")).toString();
    var run = mutastic("run", project, "--format=jsonl", "--threshold=0");
    assertThat(run.exit()).as(run.err()).isZero();
    whole = results(run.out());
    wholeFinished = finished(run.out());
    wholeReport = JSON.readTree(report().toFile());
    for (var share : List.of("--shard=1/2 --format=jsonl", "--shard=2/2")) {
      var args = new ArrayList<>(List.of("run", project, "--threshold=0"));
      args.addAll(List.of(share.split(" ")));
      var shown = mutastic(args.toArray(String[]::new));
      assertThat(shown.exit()).as(shown.err()).isZero();
    }
    shares = Path.of(project, "build", "mutastic", "shares");
  }

  @Test
  void theSharesTogetherAreTheWholeRun() throws IOException {
    var first = sharesResults(1);
    var second = sharesResults(2);

    assertThat(first).isNotEmpty();
    assertThat(second).isNotEmpty();
    assertThat(first.keySet()).doesNotContainAnyElementsOf(second.keySet());
    var together = new HashMap<>(statuses(first));
    together.putAll(statuses(second));
    assertThat(together).isEqualTo(statuses(whole));
  }

  /// The run made of the shares has each mutant's result as its share gave it, field for field, the
  /// whole run's counts, score and report, and keeps its record as a whole run's: `explain` finds a
  /// mutant of either share in it.
  @Test
  void theSharesPutTogetherAreTheWholeRun() throws IOException {
    Files.delete(Path.of(project, "build", "mutastic", "last-run.tsv"));

    var run =
        mutastic("run", project, "--from-shards=" + shares, "--format=jsonl", "--threshold=0");

    assertThat(run.exit()).as(run.err()).isZero();
    var fromShards = results(run.out());
    var sharesGave = new LinkedHashMap<>(sharesResults(1));
    sharesGave.putAll(sharesResults(2));
    assertThat(fromShards).isEqualTo(sharesGave);
    assertThat(statuses(fromShards)).isEqualTo(statuses(whole));
    var counts =
        List.of("mutants", "killed", "survived", "timeout", "noCoverage", "ignored", "score");
    var finished = finished(run.out());
    counts.forEach(count -> assertThat(finished.get(count)).isEqualTo(wholeFinished.get(count)));
    assertThat(withoutTimings(JSON.readTree(report().toFile())))
        .isEqualTo(withoutTimings(wholeReport));
    for (int share = 1; share <= 2; share++) {
      var id = sharesResults(share).keySet().iterator().next();
      var explained = mutastic("explain", id, project);
      assertThat(explained.out())
          .as(id)
          .contains(lastRun(fromShards.get(id).get("status").asString()));
    }
  }

  /// The threshold is checked on the whole run's score, as a whole run checks it.
  @Test
  void theThresholdIsTheWholeRuns() {
    var run = mutastic("run", project, "--from-shards=" + shares, "--threshold=100");

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.FAILED);
    assertThat(run.out()).contains(missedId(), "[FAIL] Below the threshold of 100");
  }

  @Test
  void aMissingShareIsRefused() {
    assertRefused(
        dir -> delete(dir.resolve("2-of-2").resolve(Shares.EVENTS)), "share 2 of 2 is missing");
  }

  @Test
  void aShareThatDidntFinishIsRefused() {
    assertRefused(
        dir -> rewrite(dir.resolve("2-of-2"), List::removeLast),
        "didn't finish: its events stop before run-finished");
  }

  @Test
  void aMutantInTwoSharesIsRefused() {
    assertRefused(
        dir -> {
          var mutant = lines(dir.resolve("1-of-2")).get(1);
          rewrite(dir.resolve("2-of-2"), lines -> lines.add(1, mutant));
        },
        "is in share 1 of 2");
  }

  @Test
  void sharesOfRunsSplitDifferentlyAreRefused() {
    assertRefused(
        dir -> replace(dir.resolve("2-of-2"), "\"of\":2", "\"of\":3"),
        "are shares of runs split differently");
  }

  @Test
  void aShareRunByAnotherMutasticIsRefused() {
    assertRefused(
        dir -> replace(dir.resolve("2-of-2"), "\"version\":\"[^\"]*\"", "\"version\":\"0.0.1\""),
        "was run by mutastic 0.0.1, and this is");
  }

  @Test
  void sharesOnDifferentCodeAreRefused() {
    assertRefused(
        dir -> replace(dir.resolve("2-of-2"), "\"code\":\"[^\"]*\"", "\"code\":\"other\""),
        "ran on different code");
  }

  /// Shares of one run on other code than the build's are refused once the build is read: the
  /// mutants are found again from this code, and the cards show its source.
  @Test
  void sharesOnOtherCodeThanThisAreRefused() {
    assertRefused(
        dir -> {
          replace(dir.resolve("1-of-2"), "\"code\":\"[^\"]*\"", "\"code\":\"other\"");
          replace(dir.resolve("2-of-2"), "\"code\":\"[^\"]*\"", "\"code\":\"other\"");
        },
        "ran on other code than this");
  }

  /// A copy of the shares' events, spoilt, refused with `message` and a usage error's status.
  private static void assertRefused(Consumer<Path> spoil, String message) {
    try {
      var dir = Files.createTempDirectory(work, "shares");
      for (int share = 1; share <= 2; share++) {
        var folder = Files.createDirectories(dir.resolve(share + "-of-2"));
        Files.copy(eventsOf(share), folder.resolve(Shares.EVENTS));
      }
      spoil.accept(dir);
      var run = mutastic("run", project, "--from-shards=" + dir);
      assertThat(run.exit()).as(run.err()).isEqualTo(Main.USAGE);
      assertThat(run.err()).contains(message);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static Path eventsOf(int share) {
    return shares.resolve(share + "-of-2").resolve(Shares.EVENTS);
  }

  private static List<String> lines(Path folder) {
    try {
      return new ArrayList<>(Files.readAllLines(folder.resolve(Shares.EVENTS)));
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static void rewrite(Path folder, Consumer<List<String>> change) {
    var lines = lines(folder);
    change.accept(lines);
    try {
      Files.write(folder.resolve(Shares.EVENTS), lines);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static void replace(Path folder, String regex, String with) {
    rewrite(folder, lines -> lines.replaceAll(line -> line.replaceFirst(regex, with)));
  }

  private static void delete(Path file) {
    try {
      Files.delete(file);
    } catch (IOException e) {
      throw new java.io.UncheckedIOException(e);
    }
  }

  private static Path report() {
    return Path.of(project, "build", "mutastic", "mutation.json");
  }

  /// Each mutant's last event in a share's events, by id.
  private static Map<String, JsonNode> sharesResults(int share) throws IOException {
    return results(Files.readString(eventsOf(share)));
  }

  /// Each mutant's last event, by id: a revised result replaces the first.
  private static Map<String, JsonNode> results(String events) {
    var found = new LinkedHashMap<String, JsonNode>();
    events(events)
        .filter(event -> event.get("reason").asString().startsWith("mutant-"))
        .forEach(event -> found.put(event.get("id").asString(), event));
    return found;
  }

  private static JsonNode finished(String events) {
    return events(events)
        .filter(event -> event.get("reason").asString().equals("run-finished"))
        .findFirst()
        .orElseThrow();
  }

  private static Stream<JsonNode> events(String events) {
    return events.lines().filter(line -> !line.isBlank()).map(JSON::readTree);
  }

  private static Map<String, String> statuses(Map<String, JsonNode> results) {
    var statuses = new HashMap<String, String>();
    results.forEach((id, event) -> statuses.put(id, event.get("status").asString()));
    return statuses;
  }

  private static String missedId() {
    return whole.entrySet().stream()
        .filter(entry -> entry.getValue().get("status").asString().equals("Survived"))
        .map(Map.Entry::getKey)
        .findFirst()
        .orElseThrow();
  }

  /// What `explain` says of the last run for a mutant with this status.
  private static String lastRun(String status) {
    return switch (status) {
      case "Killed", "Timeout" -> "caught";
      case "Survived" -> "missed: every covering test still passed";
      case "NoCoverage" -> "not covered: no test reached its line";
      default -> "skipped";
    };
  }

  /// The report without what depends on how long things took: durations, and which of a mutant's
  /// tests ran first (fastest first), so how many ran and which caught it.
  private static JsonNode withoutTimings(JsonNode report) {
    var copy = report.deepCopy();
    ((ObjectNode) copy).remove("performance");
    for (JsonNode file : copy.get("files")) {
      for (JsonNode mutant : file.get("mutants")) {
        var fields = (ObjectNode) mutant;
        fields.remove(List.of("duration", "testsCompleted", "killedBy"));
        var tests = new ArrayList<String>();
        fields.path("coveredBy").forEach(test -> tests.add(test.asString()));
        tests.sort(null);
        fields.set("coveredBy", JSON.valueToTree(tests));
      }
    }
    return copy;
  }
}
