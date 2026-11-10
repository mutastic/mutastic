package io.mutastic.report;

import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/// Code no test reaches, counted where it is. A method out of the tests' reach is one thing to fix,
/// a test that calls it, not one per line: so by method, with the lines its mutants are on, or with
/// `--each-mutant` by line.
final class NotCovered {
  private NotCovered() {}

  /// `src/…/Pricing.java:17-24 discount()` (or `:17` for one line) to how many mutants no test
  /// reaches there, in report order. `method` names a mutant's method as the output shows it.
  static Map<String, Long> counted(
      List<MutantResult> uncovered,
      SourceView sources,
      boolean byLine,
      Function<Mutant, String> method) {
    var counted = new LinkedHashMap<String, Long>();
    grouped(uncovered, sources, byLine, method)
        .forEach((where, inGroup) -> counted.put(where, (long) inGroup.size()));
    return counted;
  }

  /// The same places, each to its mutants, in report order.
  static Map<String, List<Mutant>> grouped(
      List<MutantResult> uncovered,
      SourceView sources,
      boolean byLine,
      Function<Mutant, String> method) {
    var groups = new LinkedHashMap<String, List<Mutant>>();
    for (MutantResult result : uncovered) {
      var mutant = result.mutant();
      var key =
          mutant.className()
              + "#"
              + mutant.methodName()
              + mutant.descriptor()
              + (byLine ? ":" + sources.place(mutant).line() : "");
      groups.computeIfAbsent(key, ignored -> new java.util.ArrayList<>()).add(mutant);
    }
    var grouped = new LinkedHashMap<String, List<Mutant>>();
    groups.values().forEach(inGroup -> grouped.put(where(inGroup, sources, method), inGroup));
    return grouped;
  }

  private static String where(
      List<Mutant> inGroup, SourceView sources, Function<Mutant, String> method) {
    var first = inGroup.getFirst();
    int from = inGroup.stream().mapToInt(mutant -> sources.place(mutant).line()).min().orElse(0);
    int to = inGroup.stream().mapToInt(mutant -> sources.place(mutant).line()).max().orElse(0);
    return "%s:%s %s"
        .formatted(sources.file(first), from == to ? from : from + "-" + to, method.apply(first));
  }
}
