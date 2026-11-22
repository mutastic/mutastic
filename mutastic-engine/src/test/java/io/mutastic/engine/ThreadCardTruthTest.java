package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.report.SourceView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/// A thread's call taken away, or a pool's wait that never times out, gets a card that names the
/// test that catches it. Each fixture method's claim is that test: it must pass on the method as
/// written and fail on every mutant whose card names it, in each shape javac compiles the call to.
class ThreadCardTruthTest {
  private static final String FIXTURE = "threads/Threads.java";

  private final ClaimFixture removed = new ClaimFixture(FIXTURE, "mutastic/remove-void-call");
  private final ClaimFixture discarded =
      new ClaimFixture(FIXTURE, "mutastic/remove-discarded-call");
  private final ClaimFixture conditions = new ClaimFixture(FIXTURE, "mutastic/remove-conditional");
  private final SourceView sources = new SourceView(ClaimFixture.ROOT, List.of(ClaimFixture.ROOT));

  private static final String STARTED =
      "no test waits for the thread started in %s and checks what it did";
  private static final String POOLED =
      "no test waits for the task handed to the pool in %s and checks what it did";
  private static final String UNLOCKED = "no test checks that the lock is free again after %s";
  private static final String DAEMON = "no test checks whether a thread from %s lets the JVM exit";
  private static final String HANDLED =
      "no test checks what happens when a task on a thread from %s throws";

  /// The card each method's thread call gets, by the method it's shown in.
  private static final Map<String, String> CARDS =
      Map.ofEntries(
          Map.entry("started", STARTED.formatted("`started()`")),
          Map.entry("startedInline", STARTED.formatted("`startedInline()`")),
          Map.entry("startedByABuilder", STARTED.formatted("`startedByABuilder()`")),
          Map.entry("startedVirtual", STARTED.formatted("`startedVirtual()`")),
          Map.entry("startedInALambda", STARTED.formatted("a lambda in `startedInALambda()`")),
          Map.entry("executed", POOLED.formatted("`executed()`")),
          Map.entry("executedByAnExecutor", POOLED.formatted("`executedByAnExecutor()`")),
          Map.entry("executedBySizedPool", POOLED.formatted("`executedBySizedPool()`")),
          Map.entry("submitted", POOLED.formatted("`submitted()`")),
          Map.entry("submittedToCall", POOLED.formatted("`submittedToCall()`")),
          Map.entry("locked", UNLOCKED.formatted("`locked()`")),
          Map.entry("lockedThrough", UNLOCKED.formatted("`lockedThrough()`")),
          Map.entry("readLocked", UNLOCKED.formatted("`readLocked()`")),
          Map.entry("writeLocked", UNLOCKED.formatted("`writeLocked()`")),
          Map.entry("stop", "no test checks that a thread interrupted in `stop()` stops"),
          Map.entry("pause", "no test checks that `pause()` leaves the thread interrupted"),
          Map.entry("daemon", DAEMON.formatted("`daemon()`")),
          Map.entry("daemons", DAEMON.formatted("a lambda in `daemons()`")),
          Map.entry("handled", HANDLED.formatted("a lambda in `handled()`")),
          Map.entry("handledHere", HANDLED.formatted("`handledHere()`")));

  /// The wait each method's always-true `awaitTermination` gets.
  private static final List<String> WAITS = List.of("close", "closeFirst", "closeAfterWaits");

  @Test
  void eachThreadCallsCardNamesATestThatCatchesIt() throws Exception {
    var cards = new TreeMap<String, String>();
    var failures = new ArrayList<String>();
    for (var mutant : threadMutants()) {
      var method = ClaimFixture.sourceMethod(mutant);
      var fixture = fixtureOf(mutant);
      cards.put(method, sources.advice(mutant).headline());
      if (!fixture.testedOnOriginal(method).equals("true")) {
        failures.add(method + ": the test fails on the code as written");
      }
      if (fixture.testedOn(mutant).equals("true")) {
        failures.add(ClaimFixture.describe(mutant) + ": the test the card names passes");
      }
    }
    assertThat(failures).isEmpty();
    var expected = new TreeMap<>(CARDS);
    for (var wait : WAITS) {
      expected.put(
          wait,
          "no test has `pool.awaitTermination(50, TimeUnit.MILLISECONDS)` time out in `%s()`"
              .formatted(wait));
    }
    assertThat(cards).isEqualTo(expected);
  }

  @Test
  void anExceptionFromASubmittedTaskNeverReachesTheHandler() throws Exception {
    assertThat(removed.testedOnOriginal("handledOnSubmit")).isEqualTo("false");
  }

  @Test
  void eachFixesWordsNameTheTestToWrite() {
    var fixes = new TreeMap<String, String>();
    for (var mutant : threadMutants()) {
      fixes.put(ClaimFixture.sourceMethod(mutant), sources.advice(mutant).fix());
    }
    assertThat(fixes.get("started"))
        .isEqualTo(
            "wait for the thread to finish (`join()` it, or wait on a latch its task counts down),"
                + " then assert what its task did");
    assertThat(fixes.get("submitted"))
        .isEqualTo(
            "wait until the task has run (a latch it counts down, or `shutdown()` then"
                + " `awaitTermination()`), then assert what it did");
    assertThat(fixes.get("readLocked"))
        .isEqualTo(
            "call `readLocked()`, then take the lock from another thread with `tryLock()` and"
                + " assert it succeeds (for a read lock, take the write lock)");
    assertThat(fixes.get("daemons"))
        .isEqualTo(
            "get a thread from a lambda in `daemons()` and assert that `isDaemon()` is true");
    assertThat(fixes.get("close"))
        .isEqualTo(
            "give the pool a task still running when the wait ends, and assert what `close()`"
                + " does then");
  }

  /// The mutants whose card is a thread's: each fixture method's call to the thread or pool, and
  /// its always-true wait. Every other mutant there keeps the card any call gets, or a cleanup's.
  private List<Mutant> threadMutants() {
    var found = new ArrayList<Mutant>();
    for (var fixture : List.of(removed, discarded, conditions)) {
      for (var mutant : fixture.running()) {
        var headline = sources.advice(mutant).headline();
        // A pool's `shutdown()` has a cleanup's card, which `CleanupCardTruthTest` checks.
        boolean generic =
            headline.startsWith("no test checks the effect of calling")
                || headline.startsWith("no test needs ")
                || headline.endsWith(" shuts its pool down");
        if (!generic) {
          found.add(mutant);
        }
      }
    }
    return found;
  }

  private ClaimFixture fixtureOf(Mutant mutant) {
    return switch (mutant.operator().id()) {
      case "mutastic/remove-void-call" -> removed;
      case "mutastic/remove-discarded-call" -> discarded;
      default -> conditions;
    };
  }
}
