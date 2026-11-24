package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.LikelyFlaky;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import io.mutastic.api.WatchedTest;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Decision 27's watch: a test named likely flaky starts at 0 runs held; one whose catches held
/// adds a run, and is forgotten at 3; one that caught nothing stays as it was.
class FlakyWatchTest {
  private static final String HELD = "com.example.PricingTest#held";
  private static final String IDLE = "com.example.PricingTest#idle";
  private static final String NAMED = "com.example.PricingTest#named";
  private static final String LAST = "com.example.PricingTest#last";

  @TempDir Path work;

  @Test
  void aFullRunMovesEachWatchedTestOnAsItsCatchesDid() {
    var before = Map.of(HELD, 0, IDLE, 1, NAMED, 2, LAST, 2);
    var report =
        report(caught(0, HELD), caught(1, NAMED), caught(2, LAST))
            .withLikelyFlaky(
                List.of(
                    new LikelyFlaky(
                        NAMED, Map.of(LikelyFlaky.NOT_REPEATED, List.of("m1")), List.of())));

    var after = FlakyWatch.after(before, report, List.of());

    assertThat(after.watch())
        .containsExactly(Map.entry(HELD, 1), Map.entry(IDLE, 1), Map.entry(NAMED, 0));
    assertThat(after.watched())
        .containsExactlyInAnyOrder(
            new WatchedTest(HELD, 1, false),
            new WatchedTest(IDLE, 1, false),
            new WatchedTest(LAST, 3, true));
  }

  @Test
  void aTestNamedForTheFirstTimeIsWatchedFromNoRunsHeld() {
    var report =
        report()
            .withLikelyFlaky(
                List.of(
                    new LikelyFlaky(NAMED, Map.of(LikelyFlaky.CHANGED, List.of("m0")), List.of())));

    assertThat(FlakyWatch.after(Map.of(), report, List.of()).watch())
        .containsExactly(Map.entry(NAMED, 0));
  }

  /// A run that leaves a watched test out says nothing of it and counts nothing for it.
  @Test
  void aTestTheRunLeavesOutIsNeitherSaidNorCounted() {
    var before = Map.of(HELD, 1, IDLE, 0);
    var report = report(caught(0, HELD));

    var after =
        FlakyWatch.after(before, report, List.of("PricingTest#held", "com.example.PricingTest"));

    assertThat(after.watch()).isEqualTo(before);
    assertThat(after.watched()).isEmpty();
    assertThat(FlakyWatch.unchanged(before, report, List.of("PricingTest"))).isEmpty();
    assertThat(FlakyWatch.leftOut(HELD, List.of("OtherTest", "PricingTest#idle"))).isFalse();
  }

  @Test
  void itsKeptSortedOneTestALineAndRemovedWhenEmpty() throws IOException {
    var watch = new FlakyWatch(work);
    var file = work.resolve("last-runs").resolve(FlakyWatch.FILE);

    watch.write(Map.of("b.BTest#b", 2, "a.ATest#a", 0));

    assertThat(Files.readString(file)).isEqualTo("a.ATest#a\t0\nb.BTest#b\t2\n");
    assertThat(watch.read()).containsExactly(Map.entry("a.ATest#a", 0), Map.entry("b.BTest#b", 2));

    watch.write(Map.of());

    assertThat(file).doesNotExist();
    assertThat(watch.read()).isEmpty();
  }

  @Test
  void aLineThatIsntATestAndACountIsPassedOver() throws IOException {
    var file = work.resolve("last-runs").resolve(FlakyWatch.FILE);
    Files.createDirectories(file.getParent());
    Files.writeString(file, "a.ATest#a\t1\nnot a line\nb.BTest#b\tmany\n");

    assertThat(new FlakyWatch(work).read()).containsExactly(Map.entry("a.ATest#a", 1));
  }

  private static Report report(MutantResult... results) {
    return new Report(
        "pricing", 17, List.of(results), 4, List.of(), Duration.ZERO, 1, 1, Duration.ZERO);
  }

  private static MutantResult caught(int ordinal, String test) {
    var mutant =
        new Mutant(
            "com/example/Pricing",
            "discount",
            "(I)I",
            ordinal,
            5,
            BuiltIn.BOUNDARY,
            "IF_ICMPLT",
            "IF_ICMPLE",
            Optional.empty());
    return new MutantResult(
        mutant,
        Status.CAUGHT,
        List.of(test),
        Optional.of(test),
        Optional.of("org.opentest4j.AssertionFailedError"),
        Optional.of("assertion"),
        1,
        Optional.empty(),
        Map.of(),
        Optional.empty(),
        Duration.ZERO);
  }
}
