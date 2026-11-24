package io.mutastic.engine;

import io.mutastic.api.Listener;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Status;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/// After every mutant has its result, each test that caught one runs again, once, on the unmutated
/// code: on its own, in a new worker, as a worker started part-way through a run is, and after the
/// mutants, once the workers that ran them and what their tests started have gone, so what the run
/// left outside them (a file, a database row) is still there and nothing else is. A test that fails
/// there didn't need a mutant to fail, so its catches weren't the mutants' doing.
///
/// Its catches don't count: it's left out, as a flaky test is, and each mutant it reached runs
/// again on the same worker, where the other covering tests decide, so no result rests on it. A
/// test those runs newly find catching is checked the same way. A test's catch rests on that test
/// alone, as a mutant's tests stop at the first failure, so only the tests that caught are checked:
/// a healthy run costs one run of each.
///
/// A test that fails only in a worker that has run other mutants (a static a mutant left changed)
/// passes here, in a worker that hasn't; what [ClassesToWarm] does keeps the commonest of those, a
/// static initialiser run with a mutant in, from happening.
final class FalseCatchGuard {
  /// `[engine:junit-jupiter]/[class:com.example.ATest]/[nested-class:Inner]`: a class that failed
  /// as a whole, reported by its unique id.
  private static final Pattern CLASS_ID =
      Pattern.compile("\\[class:(?<outer>[^\\]]+)\\](?<nested>(/\\[nested-class:[^\\]]+\\])*)$");

  private static final Pattern NESTED = Pattern.compile("\\[nested-class:(?<name>[^\\]]+)\\]");

  /// What progress calls this, as it goes.
  static final String STEP = "running each catching test on the unmutated code";

  /// Runs a mutant again, against the tests coverage still has.
  @FunctionalInterface
  interface Rerun {
    MutantResult run(Mutant mutant, TestWorker worker);
  }

  /// The results, with each mutant a test that fails alone reached run again without it; each such
  /// test, by the exception it failed with on its own; and the mutants run again, by test.
  record Guarded(
      List<MutantResult> results,
      Map<String, String> failingAlone,
      Map<String, List<String>> rerun) {}

  private final Coverage coverage;
  private final Supplier<TestWorker> newWorker;
  private final Rerun rerun;

  /// `newWorker` makes the worker the tests run again on, used only once one has to; `rerun` runs a
  /// mutant whose catch was false again, on it.
  FalseCatchGuard(Coverage coverage, Supplier<TestWorker> newWorker, Rerun rerun) {
    this.coverage = coverage;
    this.newWorker = newWorker;
    this.rerun = rerun;
  }

  Guarded check(List<MutantResult> results, Listener listener) {
    var current = new ArrayList<>(results);
    var found = new LinkedHashMap<String, String>();
    var checked = new HashSet<String>();
    var rerunBy = new LinkedHashMap<String, List<String>>();
    TestWorker worker = null;
    try {
      for (var toCheck = unchecked(current, checked);
          !toCheck.isEmpty();
          toCheck = unchecked(current, checked)) {
        worker = worker == null ? newWorker.get() : worker;
        checked.addAll(toCheck);
        var failing = failingUnmutated(toCheck, worker, listener);
        if (failing.isEmpty()) {
          break;
        }
        found.putAll(failing);
        rerunBy.putAll(
            runAgainWithout(
                failing.keySet(),
                current,
                coverage,
                rerun,
                worker,
                listener,
                MutantResult::withFalseCatchBy));
      }
    } finally {
      if (worker != null) {
        worker.close();
      }
    }
    return new Guarded(current, found, rerunBy);
  }

  /// The tests that caught a mutant and haven't been checked, fastest first, as a mutant's run
  /// orders them.
  private List<String> unchecked(List<MutantResult> results, Set<String> checked) {
    return results.stream()
        .filter(result -> result.status() == Status.CAUGHT)
        .flatMap(result -> result.caughtBy().stream())
        .filter(test -> !checked.contains(test))
        .distinct()
        .sorted(
            Comparator.comparing((String test) -> coverage.timeOf(test))
                .thenComparing(Comparator.naturalOrder()))
        .toList();
  }

  /// Each of `tests` that fails on the unmutated code, with the exception it failed with.
  private Map<String, String> failingUnmutated(
      List<String> tests, TestWorker worker, Listener listener) {
    var failing = new LinkedHashMap<String, String>();
    for (int index = 0; index < tests.size(); index++) {
      listener.checking(STEP, index, tests.size());
      var test = tests.get(index);
      var runnable = runnable(test);
      if (runnable.isEmpty()) {
        continue; // nothing can run it again: no evidence either way
      }
      var timeout =
          Engine.timeoutFor(
              test.contains("#") ? coverage.timeOf(test) : coverage.timeOfEveryTest());
      worker
          .unmutated(runnable.get(), timeout)
          .filter(FalseCatchGuard::failed)
          .ifPresent(run -> failing.put(test, run.failure().orElse("an unknown exception")));
    }
    return failing;
  }

  /// Whether a run on the unmutated code is evidence that the test fails there: a failure. Running
  /// out of time isn't, as load can do that, and nor is a port in use, which something else may
  /// hold.
  private static boolean failed(TestWorker.Run run) {
    return run.status() == Status.CAUGHT && !run.portClash();
  }

  /// How the worker selects `test`: a method as it is, a class that failed as a whole by its name.
  static Optional<String> runnable(String test) {
    if (test.contains("#") || !test.contains("[")) {
      return Optional.of(test);
    }
    var matcher = CLASS_ID.matcher(test);
    if (!matcher.find()) {
      return Optional.empty();
    }
    var name = new StringBuilder(matcher.group("outer"));
    var nested = NESTED.matcher(matcher.group("nested"));
    while (nested.find()) {
      name.append('$').append(nested.group("name"));
    }
    return Optional.of(name.toString());
  }

  /// Each mutant one of `tests` reached, run again without them, in place in `results`: they're
  /// left out of coverage first, so no mutant runs them again. A result one of them had caught is
  /// `marked` with that test. The mutants run again, by the tests that reached them.
  static Map<String, List<String>> runAgainWithout(
      java.util.Collection<String> tests,
      List<MutantResult> results,
      Coverage coverage,
      Rerun rerun,
      TestWorker worker,
      Listener listener,
      java.util.function.BiFunction<MutantResult, String, MutantResult> marked) {
    var leftOut = Set.copyOf(tests);
    coverage.exclude(List.copyOf(leftOut));
    var rerunBy = new LinkedHashMap<String, List<String>>();
    for (int at = 0; at < results.size(); at++) {
      var result = results.get(at);
      if (!reached(result, leftOut)) {
        continue;
      }
      result.coveredBy().stream()
          .filter(leftOut::contains)
          .forEach(
              test ->
                  rerunBy
                      .computeIfAbsent(test, ignored -> new ArrayList<>())
                      .add(result.mutant().id()));
      var again = rerun.run(result.mutant(), worker);
      var catcher = caughtByOneOf(result, leftOut);
      if (catcher != null) {
        again = marked.apply(again, catcher);
      }
      results.set(at, again);
      listener.mutantRevised(again);
    }
    return rerunBy;
  }

  /// Whether one of `tests` reached the mutant, so its result may rest on it: a miss as much as a
  /// catch, as a mutant that only passes because of what a test leaves behind isn't missed either.
  private static boolean reached(MutantResult result, Set<String> tests) {
    return result.status() != Status.SKIPPED
        && result.coveredBy().stream().anyMatch(tests::contains);
  }

  /// The test that caught the mutant, if it's one of `tests`.
  private static @Nullable String caughtByOneOf(MutantResult result, Set<String> tests) {
    return result.status() == Status.CAUGHT && result.caughtBy().filter(tests::contains).isPresent()
        ? result.caughtBy().get()
        : null;
  }
}
