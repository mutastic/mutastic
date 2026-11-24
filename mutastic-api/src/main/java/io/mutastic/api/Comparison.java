package io.mutastic.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.function.UnaryOperator;

/// This run against the last one.
///
/// With the code and mutastic unchanged, a mutant is caught or missed the same way every time
/// unless a test behaves differently between runs. So a mutant that was caught last run and is
/// missed now, or the other way round, points at the test that caught it: likely flaky. With either
/// changed, a flip is just the change at work.
///
/// @param previousScore the last run's score; none when it had no mutants to score
/// @param sameCode whether the code and tests are byte for byte what the last run had
/// @param sameTool whether the same mutastic ran
/// @param flips mutants caught in one run and not the other
/// @param otherOperators the operators the last run used, `defaults`, when they weren't this run's:
///   then nothing is compared, as a score is a share of what its operators make, and it's said so
@Internal
public record Comparison(
    OptionalDouble previousScore,
    boolean sameCode,
    boolean sameTool,
    List<Flip> flips,
    Optional<String> otherOperators) {

  /// A mutant caught in one run and not the other.
  ///
  /// @param caughtBy the test that caught it, in whichever run it was caught; empty for a timeout
  public record Flip(String id, Status before, Status after, Optional<String> caughtBy) {}

  public Comparison {
    flips = List.copyOf(flips);
  }

  public Comparison(
      OptionalDouble previousScore, boolean sameCode, boolean sameTool, List<Flip> flips) {
    this(previousScore, sameCode, sameTool, flips, Optional.empty());
  }

  /// No comparison: the last run used other operators, `last`.
  public static Comparison underOtherOperators(String last) {
    return new Comparison(OptionalDouble.empty(), false, false, List.of(), Optional.of(last));
  }

  /// Whether this run was compared with the last: it was unless the last used other operators.
  public boolean compared() {
    return otherOperators.isEmpty();
  }

  /// `not compared with the last run, which used other operators (defaults)`, when it wasn't.
  public Optional<String> notCompared() {
    return notCompared(UnaryOperator.identity());
  }

  /// The same, the last run's operators as `shown` shows them: they're read from its record, a file
  /// in the project's build.
  public Optional<String> notCompared(UnaryOperator<String> shown) {
    return otherOperators.map(
        last ->
            "not compared with the last run, which used other operators (%s)"
                .formatted(shown.apply(last)));
  }

  /// `same code`, `code changed` or `mutastic changed`: what explains a difference.
  public String sameness() {
    return !sameCode ? "code changed" : !sameTool ? "mutastic changed" : "same code";
  }

  /// Each likely flaky test and the mutants whose results it changed: empty unless the code and
  /// mutastic are the same.
  public Map<String, List<String>> likelyFlaky() {
    var tests = new LinkedHashMap<String, List<String>>();
    if (sameCode && sameTool) {
      flips.forEach(
          flip ->
              flip.caughtBy()
                  .ifPresent(
                      test ->
                          tests
                              .computeIfAbsent(test, ignored -> new java.util.ArrayList<>())
                              .add(flip.id())));
    }
    return tests;
  }
}
