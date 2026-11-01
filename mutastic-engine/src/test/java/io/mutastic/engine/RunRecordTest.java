package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Comparison;
import io.mutastic.api.Status;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RunRecordTest {
  private static final Optional<String> FLAKY =
      Optional.of("com.example.StockTest#restockingWorks");
  private static final Optional<String> SOLID = Optional.of("com.example.StockTest#reorders");

  @TempDir Path work;

  @Test
  void aMutantThatChangesResultOnTheSameCodeNamesItsCatcher() {
    var last = record("abc", Map.of("m1", caught(FLAKY), "m2", caught(SOLID), "m3", missed()));
    var now = record("abc", Map.of("m1", missed(), "m2", caught(SOLID), "m3", caught(FLAKY)));

    var comparison = now.against(last);

    assertThat(comparison.sameCode()).isTrue();
    assertThat(comparison.flips()).extracting(Comparison.Flip::id).containsExactly("m1", "m3");
    assertThat(comparison.likelyFlaky())
        .containsExactly(Map.entry(FLAKY.get(), List.of("m1", "m3")));
  }

  @Test
  void changedCodeExplainsAnyChange() {
    var last = record("abc", Map.of("m1", caught(FLAKY)));
    var now = record("def", Map.of("m1", missed()));

    var comparison = now.against(last);

    assertThat(comparison.flips()).hasSize(1);
    assertThat(comparison.likelyFlaky()).isEmpty();
  }

  @Test
  void aNewMutasticExplainsAnyChangeToo() {
    var last = record("abc", "tool-1", Map.of("m1", caught(FLAKY)));
    var now = record("abc", "tool-2", Map.of("m1", missed()));

    var comparison = now.against(last);

    assertThat(comparison.flips()).hasSize(1);
    assertThat(comparison.likelyFlaky()).isEmpty();
    assertThat(comparison.sameness()).isEqualTo("mutastic changed");
    assertThat(record("abc", Map.of()).against(record("abd", Map.of())).sameness())
        .isEqualTo("code changed");
    assertThat(record("abc", Map.of()).against(record("abc", Map.of())).sameness())
        .isEqualTo("same code");
  }

  /// Runs one after the other of two projects say nothing about each other.
  @Test
  void onlyARunOfTheSameProjectCompares() {
    var mine = record("abc", Map.of());
    var theirs =
        new RunRecord(
            "/other/classes", "abc", "tool-1", java.util.OptionalDouble.of(50.0), Map.of());
    var unknown = new RunRecord("", "abc", "tool-1", java.util.OptionalDouble.of(50.0), Map.of());

    assertThat(mine.sameProjectAs(record("def", Map.of()))).isTrue();
    assertThat(mine.sameProjectAs(theirs)).isFalse();
    assertThat(mine.sameProjectAs(unknown)).isFalse();
  }

  @Test
  void aRunWithNothingToScoreIsRecordedWithoutAScore() throws Exception {
    var file = work.resolve("last-run.tsv");
    new RunRecord("/app/classes", "abc", "tool-1", java.util.OptionalDouble.empty(), Map.of())
        .write(file);

    assertThat(RunRecord.read(file).orElseThrow().score()).isEmpty();
    assertThat(java.nio.file.Files.readString(file)).contains("score\t-\n");
  }

  @Test
  void aRecordFromBeforeToolHashesCountsAsAnotherMutastic() throws Exception {
    var file = work.resolve("last-run.tsv");
    java.nio.file.Files.writeString(
        file, "code\tabc\nscore\t50.0\nm1\tCAUGHT\t" + FLAKY.get() + "\n");

    var comparison =
        record("abc", Map.of("m1", missed())).against(RunRecord.read(file).orElseThrow());

    assertThat(comparison.sameTool()).isFalse();
    assertThat(comparison.likelyFlaky()).isEmpty();
  }

  @Test
  void aTimeoutAndACatchAreTheSameVerdict() {
    var last = record("abc", Map.of("m1", new RunRecord.Entry(Status.TIMED_OUT, Optional.empty())));
    var now = record("abc", Map.of("m1", caught(SOLID)));

    assertThat(now.against(last).flips()).isEmpty();
  }

  @Test
  void survivesAWriteAndARead() {
    var file = work.resolve("build/mutastic/last-run.tsv");
    var written = record("abc", Map.of("m1", caught(FLAKY), "m2", missed()));

    written.write(file);

    assertThat(RunRecord.read(file)).contains(written);
  }

  @Test
  void anUnreadableRecordIsNoRecord() throws Exception {
    var file = work.resolve("last-run.tsv");
    java.nio.file.Files.writeString(file, "garbage\n");

    assertThat(RunRecord.read(file)).isEmpty();
    assertThat(RunRecord.read(work.resolve("missing.tsv"))).isEmpty();
  }

  private static RunRecord record(String code, Map<String, RunRecord.Entry> results) {
    return record(code, "tool-1", results);
  }

  private static RunRecord record(String code, String tool, Map<String, RunRecord.Entry> results) {
    return new RunRecord("/app/classes", code, tool, java.util.OptionalDouble.of(50.0), results);
  }

  private static RunRecord.Entry caught(Optional<String> by) {
    return new RunRecord.Entry(Status.CAUGHT, by);
  }

  private static RunRecord.Entry missed() {
    return new RunRecord.Entry(Status.MISSED, Optional.empty());
  }
}
