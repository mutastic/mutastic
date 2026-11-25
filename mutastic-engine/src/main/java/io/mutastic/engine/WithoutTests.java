package io.mutastic.engine;

import io.mutastic.api.Listener;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Status;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/// Mutants run again without some tests whose catches don't count, in place in a run's results:
/// what [FalseCatchGuard] does with a test that fails on its own, and [LikelyFlakyGuard] with one
/// named likely flaky. The tests are left out of coverage first, so no mutant runs them again, and
/// each mutant one of them reached, caught or missed, runs again, where the other covering tests
/// decide: a miss may rest on what such a test leaves behind as much as a catch on its failing.
record WithoutTests(
    Coverage coverage, FalseCatchGuard.Rerun rerun, TestWorker worker, Listener listener) {

  /// Each mutant one of `tests` reached, run again without them; a result one of them had caught is
  /// `marked` with that test. The mutants run again, by the tests that reached them.
  Map<String, List<String>> runAgain(
      Collection<String> tests,
      List<MutantResult> results,
      BiFunction<MutantResult, String, MutantResult> marked) {
    var leftOut = Set.copyOf(tests);
    coverage.exclude(List.copyOf(leftOut));
    var rerunBy = new LinkedHashMap<String, List<String>>();
    for (int at = 0; at < results.size(); at++) {
      var result = results.get(at);
      if (reached(result, leftOut)) {
        result.coveredBy().stream()
            .filter(leftOut::contains)
            .forEach(
                test ->
                    rerunBy
                        .computeIfAbsent(test, ignored -> new ArrayList<>())
                        .add(result.mutant().id()));
        results.set(at, again(result, leftOut, marked));
      }
    }
    return rerunBy;
  }

  /// `result`'s mutant, run again without `leftOut`, and said again.
  private MutantResult again(
      MutantResult result,
      Set<String> leftOut,
      BiFunction<MutantResult, String, MutantResult> marked) {
    var again = rerun.run(result.mutant(), worker);
    var catcher = caughtByOneOf(result, leftOut);
    var revised = catcher == null ? again : marked.apply(again, catcher);
    listener.mutantRevised(revised);
    return revised;
  }

  /// Whether one of `tests` reached the mutant, so its result may rest on it.
  private static boolean reached(MutantResult result, Set<String> tests) {
    return result.status() != Status.SKIPPED
        && result.coveredBy().stream().anyMatch(tests::contains);
  }

  /// The test that caught the mutant, if it's one of `tests`: one whose catch counted, or one whose
  /// catch didn't repeat, which failed against it all the same.
  private static @Nullable String caughtByOneOf(MutantResult result, Set<String> tests) {
    if (result.status() == Status.CAUGHT && result.caughtBy().filter(tests::contains).isPresent()) {
      return result.caughtBy().get();
    }
    return result.unconfirmed().filter(tests::contains).orElse(null);
  }
}
