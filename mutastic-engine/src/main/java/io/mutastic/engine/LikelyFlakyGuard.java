package io.mutastic.engine;

import io.mutastic.api.LikelyFlaky;
import io.mutastic.api.Listener;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Status;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Supplier;

/// After the mutants and [FalseCatchGuard], the tests the run names likely flaky, and none of their
/// catches counting (decision 27). A test is named when, with the code, the tests and mutastic
/// unchanged since the last run, a mutant it caught in one of the two runs changed result; when a
/// catch of it didn't repeat as it was confirmed; or when it failed on its own with no mutant in,
/// which [FalseCatchGuard] has already dealt with.
///
/// Each test so named is left out, and each mutant it reached, caught or missed, runs again without
/// it, where the other covering tests decide, as [FalseCatchGuard] does for a test that fails on
/// its own. A catch that doesn't repeat in those runs names its test in turn. So no score,
/// threshold or gate rests on a catch by a test the run itself doesn't trust.
final class LikelyFlakyGuard {
  /// What progress calls this, as it goes.
  static final String STEP = "running again what each likely flaky test reached";

  /// The results, with each mutant a likely flaky test reached run again without it; and each test
  /// named, with why and what ran again.
  record Named(List<MutantResult> results, List<LikelyFlaky> likelyFlaky) {}

  private final Coverage coverage;
  private final FlakyHistory history;
  private final Supplier<TestWorker> newWorker;
  private final FalseCatchGuard.Rerun rerun;

  /// `newWorker` makes the worker the mutants run again on, used only once one has to.
  LikelyFlakyGuard(
      Coverage coverage,
      FlakyHistory history,
      Supplier<TestWorker> newWorker,
      FalseCatchGuard.Rerun rerun) {
    this.coverage = coverage;
    this.history = history;
    this.newWorker = newWorker;
    this.rerun = rerun;
  }

  Named check(FalseCatchGuard.Guarded guarded, Listener listener) {
    var current = new ArrayList<>(guarded.results());
    var named = namedFirst(guarded, current);
    var handled = new LinkedHashSet<>(guarded.failingAlone().keySet());
    try (var worker = new LazyWorker(newWorker)) {
      for (var toDrop = unhandled(current, named, handled);
          !toDrop.isEmpty();
          toDrop = unhandled(current, named, handled)) {
        handled.addAll(toDrop);
        listener.checking(STEP, 0, 0);
        new WithoutTests(coverage, rerun, worker.get(), listener)
            .runAgain(toDrop, current, MutantResult::withFlakyCatchBy)
            .forEach((test, mutants) -> accused(named, test).rerun().addAll(mutants));
      }
    }
    return new Named(current, named.values().stream().map(Accused::named).toList());
  }

  /// The tests named before any is dealt with: each that failed on its own, which [FalseCatchGuard]
  /// has dealt with already, and each whose catch changed since the last run.
  private Map<String, Accused> namedFirst(
      FalseCatchGuard.Guarded guarded, List<MutantResult> current) {
    var named = new LinkedHashMap<String, Accused>();
    guarded
        .failingAlone()
        .keySet()
        .forEach(
            test ->
                accused(named, test)
                    .because(
                        LikelyFlaky.FAILS_ALONE,
                        falselyCaughtBy(current, test),
                        guarded.rerun().getOrDefault(test, List.of())));
    changedSinceLastRun(current)
        .forEach((test, mutants) -> accused(named, test).because(LikelyFlaky.CHANGED, mutants));
    return named;
  }

  /// The tests named and not yet dealt with, once the catches that didn't repeat in `results` have
  /// named theirs too.
  private static List<String> unhandled(
      List<MutantResult> results, Map<String, Accused> named, Set<String> handled) {
    results.forEach(
        result ->
            result
                .unconfirmed()
                .ifPresent(
                    test ->
                        accused(named, test)
                            .because(LikelyFlaky.NOT_REPEATED, List.of(result.mutant().id()))));
    return named.keySet().stream().filter(test -> !handled.contains(test)).toList();
  }

  /// Each test that caught a mutant whose result changed since the last run, in whichever run it
  /// caught it, with those mutants: none when anything has changed since, or there's no last run.
  private Map<String, List<String>> changedSinceLastRun(List<MutantResult> results) {
    var byTest = new LinkedHashMap<String, List<String>>();
    for (var result : results) {
      var before = history.sameCode().get(result.mutant().id());
      if (before == null || !flipped(before.status(), result.status())) {
        continue;
      }
      Optional<String> catcher = result.status().detected() ? result.caughtBy() : before.caughtBy();
      catcher.ifPresent(
          test ->
              byTest.computeIfAbsent(test, ignored -> new ArrayList<>()).add(result.mutant().id()));
    }
    return byTest;
  }

  /// Caught in one run and not the other, as a comparison of the two runs counts it.
  private static boolean flipped(Status before, Status now) {
    return before.detected() != now.detected() && before != Status.SKIPPED && now != Status.SKIPPED;
  }

  /// The mutants `test` caught that its failing on its own took away.
  private static List<String> falselyCaughtBy(List<MutantResult> results, String test) {
    return results.stream()
        .filter(result -> result.falseCatchBy().filter(test::equals).isPresent())
        .map(result -> result.mutant().id())
        .toList();
  }

  private static Accused accused(Map<String, Accused> named, String test) {
    return named.computeIfAbsent(
        test, ignored -> new Accused(test, new LinkedHashMap<>(), new ArrayList<>()));
  }

  /// A test some signal named: the mutants whose results it changed, by why, and what ran again.
  private record Accused(String test, Map<String, Set<String>> byReason, List<String> rerun) {
    Accused because(String reason, List<String> changed) {
      return because(reason, changed, List.of());
    }

    Accused because(String reason, List<String> changed, List<String> ranAgain) {
      byReason.computeIfAbsent(reason, ignored -> new TreeSet<>()).addAll(changed);
      rerun.addAll(ranAgain);
      return this;
    }

    LikelyFlaky named() {
      var mutants = new LinkedHashMap<String, List<String>>();
      byReason.forEach((reason, ids) -> mutants.put(reason, List.copyOf(ids)));
      return new LikelyFlaky(test, mutants, rerun);
    }
  }
}
