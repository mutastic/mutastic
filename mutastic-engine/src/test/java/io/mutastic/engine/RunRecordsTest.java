package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Status;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunRecordsTest {
  private static final String PROJECT = "/code/app/build/classes/java/main";

  @TempDir Path work;

  private RunRecord run(String operators, double score) {
    return run(PROJECT, operators, score);
  }

  private static RunRecord run(String project, String operators, double score) {
    return new RunRecord(
        project,
        operators,
        "code",
        "tool",
        OptionalDouble.of(score),
        Map.of("m1", new RunRecord.Entry(Status.CAUGHT, Optional.empty())));
  }

  private Optional<Double> comparedScore(RunRecords records, String operators) {
    return records.comparableTo(run(operators, 0)).map(found -> found.score().orElseThrow());
  }

  private long filesAside() throws IOException {
    try (Stream<Path> files = Files.list(work.resolve("last-runs"))) {
      return files.count();
    }
  }

  @Test
  void aRunUnderOtherOperatorsLeavesTheComparisonInPlace() {
    var records = new RunRecords(work);
    records.keep(run("a,b", 50));
    records.keep(run("a,b,c", 60));

    assertThat(comparedScore(records, "a,b")).contains(50.0);
    assertThat(comparedScore(records, "a,b,c")).contains(60.0);
    assertThat(comparedScore(records, "a")).isEmpty();
  }

  @Test
  void theLatestRunIsStillInLastRunTsv() {
    var records = new RunRecords(work);
    records.keep(run("a,b", 50));
    records.keep(run("a,b,c", 60));

    assertThat(RunRecord.read(work.resolve("last-run.tsv")).orElseThrow().operators())
        .isEqualTo("a,b,c");
  }

  @Test
  void aSetHasOneRecordTheNewest() throws IOException {
    var records = new RunRecords(work);
    records.keep(run("a", 10));
    records.keep(run("b", 20));
    records.keep(run("a", 30));
    records.keep(run("b", 40));
    records.keep(run("a", 50));

    assertThat(comparedScore(records, "a")).contains(50.0);
    assertThat(comparedScore(records, "b")).contains(40.0);
    assertThat(filesAside()).isEqualTo(1);
    assertThat(records.newestFirst()).extracting(RunRecord::operators).containsExactly("a", "b");
  }

  @Test
  void theFilesAreBoundedAndTheLongestUnusedGoFirst() throws IOException {
    var records = new RunRecords(work);
    int sets = RunRecords.KEPT_BESIDE_THE_LATEST + 3;
    for (int set = 0; set < sets; set++) {
      records.keep(run("set" + set, set));
      // Modified times can tie on a coarse clock: these say the order outright.
      var aside = work.resolve("last-run.tsv");
      Files.setLastModifiedTime(aside, java.nio.file.attribute.FileTime.fromMillis(set * 1000L));
    }

    assertThat(filesAside()).isEqualTo(RunRecords.KEPT_BESIDE_THE_LATEST);
    assertThat(comparedScore(records, "set0")).isEmpty();
    assertThat(comparedScore(records, "set1")).isEmpty();
    assertThat(comparedScore(records, "set2")).contains(2.0);
    assertThat(records.newestFirst().getFirst().operators()).isEqualTo("set" + (sets - 1));
    assertThat(records.newestFirst().get(1).operators()).isEqualTo("set" + (sets - 2));
  }

  /// An older mutastic's record has no operators, or no project: it's never compared, and never set
  /// aside as if it were of a set.
  @Test
  void aRecordFromBeforeOperatorsWereKeptIsComparedWithNothing() throws IOException {
    Files.writeString(
        work.resolve("last-run.tsv"),
        "project\t" + PROJECT + "\ncode\tcode\ntool\ttool\nscore\t70.0\nm1\tCAUGHT\t-\n");
    var records = new RunRecords(work);

    assertThat(comparedScore(records, "a")).isEmpty();
    records.keep(run("a", 10));

    assertThat(work.resolve("last-runs")).doesNotExist();
    assertThat(comparedScore(records, "a")).contains(10.0);
  }

  /// The file before this one kept a set too, and is compared as it was.
  @Test
  void aRecordOfASetFromAnOlderMutasticStillCompares() throws IOException {
    Files.writeString(
        work.resolve("last-run.tsv"),
        "project\t"
            + PROJECT
            + "\noperators\ta\ncode\tcode\ntool\ttool\nscore\t70.0\nm1\tCAUGHT\t-\n");

    assertThat(comparedScore(new RunRecords(work), "a")).contains(70.0);
  }

  @Test
  void anUnreadableRecordIsNoRecord() throws IOException {
    Files.writeString(work.resolve("last-run.tsv"), "not\ta record");
    var records = new RunRecords(work);

    assertThat(comparedScore(records, "a")).isEmpty();
    records.keep(run("a", 10));

    assertThat(comparedScore(records, "a")).contains(10.0);
  }

  /// What a file says it's of decides, not its name: this one's in the place of set `b`'s.
  @Test
  void aFileIsReadForWhatItSaysItIs() throws IOException {
    var records = new RunRecords(work);
    records.keep(run("b", 20));
    records.keep(run("a", 10));
    Path asideForB;
    try (Stream<Path> files = Files.list(work.resolve("last-runs"))) {
      asideForB = files.findFirst().orElseThrow();
    }
    run("c", 99).write(asideForB);

    assertThat(comparedScore(records, "b")).isEmpty();
  }

  @Test
  void anotherProjectsRecordIsNeitherComparedNorKept() {
    var records = new RunRecords(work);
    records.keep(run("/elsewhere/classes", "a", 90));
    records.keep(run("b", 20));

    assertThat(comparedScore(records, "a")).isEmpty();
    assertThat(work.resolve("last-runs")).doesNotExist();
  }
}
