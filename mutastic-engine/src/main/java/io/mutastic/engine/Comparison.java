package io.mutastic.engine;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// This run against the last one.
///
/// With the code unchanged, a mutant is caught or missed the same way every time unless a test
/// behaves differently between runs. So a mutant that was caught last run and is missed now,
/// or the other way round, points at the test that caught it: likely flaky. With the code
/// changed, a flip is just the change at work.
///
/// @param previousScore the last run's score
/// @param sameCode whether the code and tests are byte for byte what the last run had
/// @param flips mutants caught in one run and not the other
public record Comparison(double previousScore, boolean sameCode, List<Flip> flips) {

  /// @param caughtBy the test that caught it, in whichever run it was caught; empty for a timeout
  public record Flip(String id, Status before, Status after, Optional<String> caughtBy) {}

  public Comparison {
    flips = List.copyOf(flips);
  }

  /// Each likely flaky test and the mutants whose results it changed: empty unless the code is
  /// the same.
  public Map<String, List<String>> likelyFlaky() {
    var tests = new LinkedHashMap<String, List<String>>();
    if (sameCode) {
      flips.forEach(flip -> flip.caughtBy().ifPresent(test ->
          tests.computeIfAbsent(test, ignored -> new java.util.ArrayList<>()).add(flip.id())));
    }
    return tests;
  }
}
