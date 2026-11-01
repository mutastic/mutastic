package io.mutastic.report;

import io.mutastic.engine.Comparison;
import io.mutastic.engine.MutantResult;
import io.mutastic.engine.Report;
import io.mutastic.engine.Status;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

/// Tests whose catches can't be trusted, and why. Three signals, strongest first:
///
/// 1. **Changed between runs.** With the code unchanged, a mutant it caught in one run and not the
///    other.
/// 2. **Didn't repeat.** With catches confirmed, it failed against a mutant and then passed against
///    the same mutant.
/// 3. **Unrelated catches** (a note, not a verdict). It caught mutants in several different
///    methods, each time with the same exception thrown in its own code. A test that fails the same
///    way whatever changed may be failing for its own reasons; it may also just be a test that
///    calls a lot of code.
final class Suspects {
  /// The fewest different methods before unrelated catches are worth a note.
  static final int UNRELATED_METHODS = 3;

  private Suspects() {}

  record Suspect(String test, List<String> mutants) {}

  static List<Suspect> changedBetweenRuns(Optional<Comparison> comparison) {
    return comparison
        .map(
            found ->
                found.likelyFlaky().entrySet().stream()
                    .map(entry -> new Suspect(entry.getKey(), entry.getValue()))
                    .toList())
        .orElse(List.of());
  }

  static List<Suspect> unconfirmed(Report report) {
    var byTest = new TreeMap<String, List<String>>();
    report
        .results()
        .forEach(
            result ->
                result
                    .unconfirmed()
                    .ifPresent(
                        test ->
                            byTest
                                .computeIfAbsent(test, ignored -> new ArrayList<>())
                                .add(result.mutant().id())));
    return byTest.entrySet().stream()
        .map(entry -> new Suspect(entry.getKey(), entry.getValue()))
        .toList();
  }

  /// A test that caught mutants in several unrelated methods with the same exception from its own
  /// code.
  ///
  /// @param failure the exception every one of these catches failed with
  record UnrelatedCatches(String test, String failure, List<String> mutants, long methods) {}

  static List<UnrelatedCatches> unrelatedCatches(Report report) {
    return catchesFromTestCodeByTest(report).entrySet().stream()
        .flatMap(entry -> unrelatedCatches(entry.getKey(), entry.getValue()).stream())
        .toList();
  }

  /// Each test's catches whose exception was thrown in the test's own code, in report order.
  private static Map<String, List<MutantResult>> catchesFromTestCodeByTest(Report report) {
    var byTest = new LinkedHashMap<String, List<MutantResult>>();
    report.results().stream()
        .filter(
            result ->
                result.status() == Status.CAUGHT
                    && result.failureOrigin().equals(Optional.of("test")))
        .forEach(
            result ->
                byTest
                    .computeIfAbsent(result.caughtBy().orElseThrow(), ignored -> new ArrayList<>())
                    .add(result));
    return byTest;
  }

  private static Optional<UnrelatedCatches> unrelatedCatches(
      String test, List<MutantResult> catches) {
    var failures = catches.stream().map(result -> result.failure().orElse("")).distinct().toList();
    long methods = distinctMethods(catches);
    return failures.size() == 1 && methods >= UNRELATED_METHODS
        ? Optional.of(
            new UnrelatedCatches(
                test,
                failures.getFirst(),
                catches.stream().map(result -> result.mutant().id()).toList(),
                methods))
        : Optional.empty();
  }

  private static long distinctMethods(List<MutantResult> catches) {
    return catches.stream()
        .map(result -> result.mutant().className() + "#" + result.mutant().methodName())
        .distinct()
        .count();
  }

  /// One entry per likely flaky test, with every mutant it changed and which signals named it:
  /// `changed-since-last-run`, `catch-not-repeated`.
  static List<Map<String, Object>> json(Optional<Comparison> comparison, Report report) {
    var byTest = new LinkedHashMap<String, Accused>();
    add(byTest, changedBetweenRuns(comparison), "changed-since-last-run");
    add(byTest, unconfirmed(report), "catch-not-repeated");
    return byTest.values().stream().map(Accused::json).toList();
  }

  /// A test some signal named: the mutants it changed and the signals that named it.
  private record Accused(String test, SortedSet<String> mutants, List<String> reasons) {
    Map<String, Object> json() {
      var entry = new LinkedHashMap<String, Object>();
      entry.put("test", test);
      entry.put("mutants", mutants);
      entry.put("reasons", reasons);
      return entry;
    }
  }

  private static void add(Map<String, Accused> byTest, List<Suspect> suspects, String reason) {
    for (var suspect : suspects) {
      var accused =
          byTest.computeIfAbsent(
              suspect.test(), test -> new Accused(test, new TreeSet<>(), new ArrayList<>()));
      accused.mutants().addAll(suspect.mutants());
      accused.reasons().add(reason);
    }
  }
}
