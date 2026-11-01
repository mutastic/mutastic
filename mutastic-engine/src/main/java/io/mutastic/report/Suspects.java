package io.mutastic.report;

import io.mutastic.engine.Comparison;
import io.mutastic.engine.MutantResult;
import io.mutastic.engine.Report;
import io.mutastic.engine.Status;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/// Tests whose catches can't be trusted, and why. Three signals, strongest first:
///
/// 1. **Changed between runs.** With the code unchanged, a mutant it caught in one run and not
///    the other.
/// 2. **Didn't repeat.** With catches confirmed, it failed against a mutant and then passed
///    against the same mutant.
/// 3. **Unrelated catches** (a note, not a verdict). It caught mutants in several different
///    methods, each time with the same exception thrown in its own code. A test that fails the
///    same way whatever changed may be failing for its own reasons; it may also just be a test
///    that calls a lot of code.
final class Suspects {
  /// The fewest different methods before unrelated catches are worth a note.
  static final int UNRELATED_METHODS = 3;

  private Suspects() {}

  record Suspect(String test, List<String> mutants) {}

  static List<Suspect> changedBetweenRuns(Optional<Comparison> comparison) {
    return comparison.map(found -> found.likelyFlaky().entrySet().stream()
        .map(entry -> new Suspect(entry.getKey(), entry.getValue())).toList()).orElse(List.of());
  }

  static List<Suspect> unconfirmed(Report report) {
    var byTest = new TreeMap<String, List<String>>();
    report.results().forEach(result -> result.unconfirmed().ifPresent(test ->
        byTest.computeIfAbsent(test, ignored -> new java.util.ArrayList<>()).add(result.mutant().id())));
    return byTest.entrySet().stream().map(entry -> new Suspect(entry.getKey(), entry.getValue())).toList();
  }

  /// @param failure the exception every one of these catches failed with
  record UnrelatedCatches(String test, String failure, List<String> mutants, long methods) {}

  static List<UnrelatedCatches> unrelatedCatches(Report report) {
    var byTest = new LinkedHashMap<String, List<MutantResult>>();
    report.results().stream()
        .filter(result -> result.status() == Status.CAUGHT && result.failureOrigin().equals(Optional.of("test")))
        .forEach(result -> byTest.computeIfAbsent(result.caughtBy().orElseThrow(), ignored -> new java.util.ArrayList<>())
            .add(result));
    return byTest.entrySet().stream().flatMap(entry -> {
      var catches = entry.getValue();
      var failures = catches.stream().map(result -> result.failure().orElse("")).distinct().toList();
      long methods = catches.stream().map(result -> result.mutant().className() + "#" + result.mutant().methodName())
          .distinct().count();
      return failures.size() == 1 && methods >= UNRELATED_METHODS
          ? java.util.stream.Stream.of(new UnrelatedCatches(entry.getKey(), failures.getFirst(),
              catches.stream().map(result -> result.mutant().id()).toList(), methods))
          : java.util.stream.Stream.empty();
    }).toList();
  }

  /// One entry per likely flaky test, with every mutant it changed and which signals named it:
  /// `changed-since-last-run`, `catch-not-repeated`.
  static List<Map<String, Object>> json(Optional<Comparison> comparison, Report report) {
    var byTest = new LinkedHashMap<String, Map<String, Object>>();
    add(byTest, changedBetweenRuns(comparison), "changed-since-last-run");
    add(byTest, unconfirmed(report), "catch-not-repeated");
    return List.copyOf(byTest.values());
  }

  @SuppressWarnings("unchecked")
  private static void add(Map<String, Map<String, Object>> byTest, List<Suspect> suspects, String reason) {
    for (var suspect : suspects) {
      var entry = byTest.computeIfAbsent(suspect.test(), test -> {
        var fresh = new LinkedHashMap<String, Object>();
        fresh.put("test", test);
        fresh.put("mutants", new java.util.TreeSet<String>());
        fresh.put("reasons", new java.util.ArrayList<String>());
        return fresh;
      });
      ((java.util.TreeSet<String>) entry.get("mutants")).addAll(suspect.mutants());
      ((List<String>) entry.get("reasons")).add(reason);
    }
  }
}
