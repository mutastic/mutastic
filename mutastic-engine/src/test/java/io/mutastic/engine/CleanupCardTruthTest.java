package io.mutastic.engine;

import static org.assertj.core.api.Assertions.assertThat;

import io.mutastic.api.Mutant;
import io.mutastic.report.SourceView;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/// A call that releases something, taken away, gets a card that names the test that catches it.
/// Each fixture method's claim is that test: it must pass on the method as written and fail on
/// every mutant whose card names it, in each shape javac compiles the call to: in a `finally`
/// (every copy taken at once), in a `catch`, behind an `if`, in a lambda, through an interface.
class CleanupCardTruthTest {
  private static final String FIXTURE = "cleanup/Cleanups.java";

  private final ClaimFixture removed = new ClaimFixture(FIXTURE, "mutastic/remove-void-call");
  private final ClaimFixture discarded =
      new ClaimFixture(FIXTURE, "mutastic/remove-discarded-call");
  private final SourceView sources = new SourceView(ClaimFixture.ROOT, List.of(ClaimFixture.ROOT));

  private static final String CLOSED = "no test checks that the resource is closed after %s";
  private static final String ONE_WAY =
      "no test takes the way through %s that closes the resource and checks it's closed";
  private static final String SHUTDOWN = "no test checks that %s shuts its pool down";

  /// The card each method's call gets, by the method it's shown in.
  private static final Map<String, String> CARDS =
      Map.ofEntries(
          Map.entry("inFinally", CLOSED.formatted("`inFinally()`")),
          Map.entry("throughTheInterface", CLOSED.formatted("`throughTheInterface()`")),
          Map.entry("inCatch", ONE_WAY.formatted("`inCatch()`")),
          Map.entry("whenDone", ONE_WAY.formatted("`whenDone()`")),
          Map.entry("inALambda", ONE_WAY.formatted("a lambda in `inALambda()`")),
          Map.entry("quietly", CLOSED.formatted("`quietly()`")),
          Map.entry("valid", "no test checks that the connection goes back after `valid()`"),
          Map.entry(
              "executed",
              "no test checks this `close()` in `executed()`: closing the connection closes it"
                  + " too"),
          Map.entry("ranOn", SHUTDOWN.formatted("`ranOn()`")),
          Map.entry("stoppedNow", SHUTDOWN.formatted("`stoppedNow()`")),
          Map.entry("hooked", "no test checks that `hooked()` registers its shutdown hook"),
          Map.entry(
              "released",
              "no test checks that `released()` releases the resource now, not when the garbage"
                  + " collector gets to it"));

  @Test
  void eachCleanupCallsCardNamesATestThatCatchesIt() throws Exception {
    var cards = new TreeMap<String, String>();
    var failures = new ArrayList<String>();
    for (var mutant : cleanupMutants()) {
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
    assertThat(cards).isEqualTo(new TreeMap<>(CARDS));
  }

  @Test
  void eachFixesWordsNameTheTestToWrite() {
    var fixes = new TreeMap<String, String>();
    for (var mutant : cleanupMutants()) {
      fixes.put(ClaimFixture.sourceMethod(mutant), sources.advice(mutant).fix());
    }
    assertThat(fixes.get("inFinally"))
        .isEqualTo(
            "pass in a resource you can inspect (a stub with a closed flag, or a spy) and assert"
                + " it's closed when `inFinally()` returns, and when it throws");
    assertThat(fixes.get("inCatch"))
        .isEqualTo(
            "pass in a resource you can inspect (a stub with a closed flag, or a spy), take the"
                + " way to this `close()` (if it's in a `catch`, make the work throw), and assert"
                + " it's closed");
    assertThat(fixes.get("valid"))
        .isEqualTo(
            "take connections from a `DataSource` you pass in, then verify `close()` on a mock,"
                + " or use a pool of one with a short timeout and call `valid()` twice");
    assertThat(fixes.get("ranOn"))
        .isEqualTo(
            "pass in the executor (or a factory for it) and assert `isShutdown()` after"
                + " `ranOn()`, or make the pool in a try-with-resources (JDK 19 and later)");
    assertThat(fixes.get("hooked"))
        .isEqualTo(
            "call `hooked()`, then assert that `Runtime.getRuntime().removeShutdownHook(hook)`"
                + " returns true");
    assertThat(fixes.get("released"))
        .isEqualTo("call `released()`, then assert the cleanup's effect right away");
  }

  /// The mutants of each fixture method's releasing call. The helpers' own calls, and the claims',
  /// aren't checked here.
  private List<Mutant> cleanupMutants() {
    return Stream.concat(removed.running().stream(), discarded.running().stream())
        .filter(mutant -> CARDS.containsKey(ClaimFixture.sourceMethod(mutant)))
        .filter(
            mutant ->
                Stream.of(".close()V", "closeQuietly", "shutdown", "addShutdownHook", ".clean()V")
                    .anyMatch(mutant.original()::contains))
        .toList();
  }

  private ClaimFixture fixtureOf(Mutant mutant) {
    return removed.running().contains(mutant) ? removed : discarded;
  }
}
