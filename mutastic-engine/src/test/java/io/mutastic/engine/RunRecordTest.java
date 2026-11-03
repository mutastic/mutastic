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

  @Test
  void theCodeHashChangesWithAClassFileAndNothingElse() throws Exception {
    var classes = work.resolve("classes");
    var tests = work.resolve("test-classes");
    java.nio.file.Files.createDirectories(classes.resolve("com/example"));
    java.nio.file.Files.createDirectories(tests);
    var pricing = classes.resolve("com/example/Pricing.class");
    java.nio.file.Files.write(pricing, new byte[] {1, 2, 3});
    var project = project(List.of(classes, work.resolve("missing")), List.of(tests));

    var before = RunRecord.codeOf(project);
    java.nio.file.Files.writeString(classes.resolve("notes.txt"), "not code");
    var withNotes = RunRecord.codeOf(project);
    java.nio.file.Files.write(pricing, new byte[] {1, 2, 4});
    var changed = RunRecord.codeOf(project);
    java.nio.file.Files.write(tests.resolve("PricingTest.class"), new byte[] {9});
    var withTest = RunRecord.codeOf(project);

    assertThat(withNotes).isEqualTo(before);
    assertThat(changed).isNotEqualTo(before);
    assertThat(withTest).isNotEqualTo(changed);
  }

  @Test
  void aRecordIsOfItsProjectAndKeepsEachResult() throws Exception {
    var classes = work.resolve("classes");
    java.nio.file.Files.createDirectories(classes);
    var project = project(List.of(classes), List.of());
    var other = project(List.of(work.resolve("elsewhere")), List.of());
    var report =
        new io.mutastic.api.Report(
            "pricing",
            17,
            List.of(),
            0,
            List.of(),
            java.time.Duration.ZERO,
            1,
            1,
            java.time.Duration.ZERO);

    var recorded = RunRecord.of(project, "tool-1", report);
    var withOtherTests =
        RunRecord.of(project, List.of(work.resolve("their-tests")), "tool-1", report);

    assertThat(recorded.isOf(project)).isTrue();
    assertThat(recorded.isOf(other)).isFalse();
    assertThat(recorded.project()).isEqualTo(classes.toAbsolutePath().normalize().toString());
    assertThat(recorded.tool()).isEqualTo("tool-1");
    assertThat(recorded.score()).isEmpty();
    assertThat(withOtherTests.code()).isEqualTo(recorded.code());
    assertThat(
            new RunRecord("", "abc", "t", java.util.OptionalDouble.empty(), Map.of()).isOf(project))
        .isFalse();
  }

  @Test
  void theToolHashCoversEachFilesPathAndBytes() throws Exception {
    var jar = work.resolve("tool/engine.jar");
    java.nio.file.Files.createDirectories(jar.getParent());
    java.nio.file.Files.write(jar, new byte[] {1});
    var first = RunRecord.toolOf(List.of(work.resolve("tool")));

    java.nio.file.Files.write(jar, new byte[] {2});
    var rebuilt = RunRecord.toolOf(List.of(work.resolve("tool")));
    java.nio.file.Files.move(jar, work.resolve("tool/worker.jar"));
    var renamed = RunRecord.toolOf(List.of(work.resolve("tool")));

    assertThat(first).hasSize(64).isNotEqualTo(rebuilt);
    assertThat(renamed).isNotEqualTo(rebuilt);
    assertThat(RunRecord.toolOf(List.of(work.resolve("tool")))).isEqualTo(renamed);
  }

  private static io.mutastic.api.Project project(List<Path> classes, List<Path> tests) {
    return new io.mutastic.api.Project(
        "pricing",
        Path.of("java"),
        List.of(),
        Path.of("."),
        classes,
        List.of(),
        tests,
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        io.mutastic.api.TestSelection.ALL,
        Map.of());
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
