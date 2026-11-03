package io.mutastic.report;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.MutantResult;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

/// Missed mutants as the gaps they point at. The mutants on one line of one method are one finding:
/// one test there usually catches them all (the edge of a condition catches its boundary and its
/// removal alike), and a card each would ask for the same test again and again. Each mutant is
/// still counted, and named, on its own.
///
/// @param mutants the lead first, the one its card is written about: one with a suggested test if
///   any has one, then a boundary, whose edge is the most exact thing to test
record Finding(List<MutantResult> mutants) {
  Finding {
    mutants = List.copyOf(mutants);
  }

  /// `missed`, in its order, one finding per line of each method; with `eachMutant`, one per
  /// mutant.
  static List<Finding> of(List<MutantResult> missed, boolean eachMutant) {
    if (eachMutant) {
      return missed.stream().map(result -> new Finding(List.of(result))).toList();
    }
    var byLine = new LinkedHashMap<String, List<MutantResult>>();
    for (MutantResult result : missed) {
      var mutant = result.mutant();
      byLine
          .computeIfAbsent(
              mutant.className()
                  + "#"
                  + mutant.methodName()
                  + mutant.descriptor()
                  + ":"
                  + mutant.line(),
              line -> new ArrayList<>())
          .add(result);
    }
    return byLine.values().stream().map(Finding::led).toList();
  }

  private static Finding led(List<MutantResult> onLine) {
    return new Finding(
        onLine.stream()
            .sorted(
                Comparator.comparing((MutantResult result) -> result.suggestion().isEmpty())
                    .thenComparing(result -> result.mutant().operator() != BuiltIn.BOUNDARY))
            .toList());
  }

  MutantResult lead() {
    return mutants.getFirst();
  }

  /// The mutants after the lead.
  List<MutantResult> others() {
    return mutants.subList(1, mutants.size());
  }

  /// `m855c8e3c,m2e491c40`: every id, as `verify` and `--only` take them.
  String ids() {
    return mutants.stream().map(result -> result.mutant().id()).collect(Collectors.joining(","));
  }

  /// How many mutants are in `findings`.
  static int mutantsIn(List<Finding> findings) {
    return findings.stream().mapToInt(finding -> finding.mutants().size()).sum();
  }
}
