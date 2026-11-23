package io.mutastic.cli;

import io.mutastic.api.Mutant;
import io.mutastic.engine.TestTimes;
import io.mutastic.report.HumanText;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.UnaryOperator;

/// What `mutants` says a set of operators would add to a run's tests, from the times a run's
/// coverage pass kept ([TestTimes]): the lines it prints before the count. It never guesses: with
/// no record, or one of other code or other operators, or mutants where it measured nothing, it
/// says why there's no estimate instead.
final class Estimates {
  private final UnaryOperator<String> setNamed;

  /// `setNamed` names a set of operators by its ids: `defaults`, or `5 operators`.
  Estimates(UnaryOperator<String> setNamed) {
    this.setNamed = setNamed;
  }

  /// The code and the operators the project runs now, as a record says its own.
  ///
  /// @param code the hash of the code under test
  /// @param operators the ids of the operators the project runs unless told others
  record Now(String code, String operators) {}

  /// The line `mutants --operators=…` prints for `listed`, by the record kept.
  String of(Optional<TestTimes> recorded, Now now, List<Mutant> listed) {
    if (recorded.isEmpty()) {
      return "no estimate: no run is recorded here yet; run once to get estimates, or add"
          + " --estimate";
    }
    var times = recorded.get();
    return stale(times, now).orElseGet(() -> beyondBase(times, times.beyondBase(listed)));
  }

  /// Why `times` says nothing of the project now, if it doesn't: other code, or other operators.
  private Optional<String> stale(TestTimes times, Now now) {
    if (!times.code().equals(now.code())) {
      return Optional.of("no estimate: the code has changed since " + sourceOf(times));
    }
    if (!times.operators().equals(now.operators())) {
      return Optional.of(
          "no estimate: %s was under %s, not this project's %s"
              .formatted(
                  sourceOf(times),
                  setNamed.apply(times.operators()),
                  setNamed.apply(now.operators())));
    }
    return Optional.empty();
  }

  /// What `more`, the mutants beyond the record's own, would add to its tests' time.
  private String beyondBase(TestTimes times, List<Mutant> more) {
    var estimate = times.estimate(more);
    var source = sourceOf(times);
    var base = setNamed.apply(times.operators());
    if (estimate.unmeasured() > 0) {
      return ("no estimate: %d of the %d more mutants %s in code %s didn't measure; add"
              + " --estimate to measure it all")
          .formatted(
              estimate.unmeasured(),
              more.size(),
              estimate.unmeasured() == 1 ? "is" : "are",
              source);
    }
    if (more.isEmpty()) {
      return "estimate: no more mutants than %s, from %s".formatted(base, source);
    }
    return "estimate: %d more %s than %s, up to %s more of tests, from %s"
        .formatted(
            more.size(), mutants(more.size()), base, HumanText.seconds(estimate.upTo()), source);
  }

  /// The line `mutants --estimate` prints for one named set, `stronger`, of `mutants`.
  String ofSet(TestTimes times, String set, List<Mutant> mutants) {
    var estimate = times.estimate(mutants);
    return "estimate: %s, %d %s, up to %s of tests, from %s"
        .formatted(
            set,
            mutants.size(),
            mutants(mutants.size()),
            HumanText.seconds(estimate.upTo()),
            sourceOf(times));
  }

  private static String mutants(int count) {
    return count == 1 ? "mutant" : "mutants";
  }

  /// `the run of 22 Nov 2026 14:03 UTC`: which record an estimate came from.
  static String sourceOf(TestTimes times) {
    return "the %s of %s".formatted(times.kind(), dated(times.at()));
  }

  /// `22 Nov 2026 14:03 UTC`, alike wherever it's read.
  static String dated(Instant at) {
    return DATE.format(at);
  }

  private static final DateTimeFormatter DATE =
      DateTimeFormatter.ofPattern("d MMM yyyy HH:mm 'UTC'", Locale.ENGLISH)
          .withZone(ZoneOffset.UTC);
}
