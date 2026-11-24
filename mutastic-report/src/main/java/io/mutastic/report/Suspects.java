package io.mutastic.report;

import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import io.mutastic.api.WatchedTest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// Tests whose catches can't be trusted, and why. The engine names a test likely flaky when, with
/// the code unchanged, a mutant it caught in one run and not the other; when a catch of it didn't
/// repeat as it was confirmed; or when it failed on its own: none of its catches count, and the run
/// says so ([Report#likelyFlaky]). Beside those, a note, not a verdict:
///
/// - **Unrelated catches**. It caught mutants in several different methods, each time with the same
///   exception thrown in its own code. A test that fails the same way whatever changed may be
///   failing for its own reasons; it may also just be a test that calls a lot of code.
final class Suspects {
  /// The fewest different methods before unrelated catches are worth a note.
  static final int UNRELATED_METHODS = 3;

  private Suspects() {}

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

  /// One entry per likely flaky test: every mutant it changed, the signals that named it, and the
  /// mutants that ran again without it.
  static List<Map<String, Object>> json(Report report) {
    return report.likelyFlaky().stream()
        .map(
            named -> {
              var entry = new LinkedHashMap<String, Object>();
              entry.put("test", named.test());
              entry.put("mutants", named.mutants());
              entry.put("reasons", named.reasons());
              entry.put("rerun", named.rerun());
              return (Map<String, Object>) entry;
            })
        .toList();
  }

  /// One entry per test watched since an earlier run named it, as this run left it.
  static List<Map<String, Object>> watched(Report report) {
    return report.watched().stream()
        .map(
            watched -> {
              var entry = new LinkedHashMap<String, Object>();
              entry.put("test", watched.test());
              entry.put("held", watched.held());
              entry.put("needed", WatchedTest.NEEDED);
              entry.put("confirmations", WatchedTest.CONFIRMATIONS);
              if (watched.released()) {
                entry.put("released", true);
              }
              return (Map<String, Object>) entry;
            })
        .toList();
  }
}
