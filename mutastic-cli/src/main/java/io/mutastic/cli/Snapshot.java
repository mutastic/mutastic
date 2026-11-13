package io.mutastic.cli;

import io.mutastic.api.Status;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// What progress shows of a phase at one moment: what's under way, its counts and its clock.
///
/// @param doing what the phase is doing: the build read, the build tool's task, or the step after
///   the mutants
/// @param below the build tool's latest line, under the live line
/// @param done how many of `total` are done: tests in the coverage pass, mutants, or the step's
/// @param counts the mutants' results so far, by status
/// @param elapsed the phase's own clock
/// @param estimate how far the mutants are by what each should take, and what's left
/// @param workers what each worker is running, empty for an idle one
/// @param keys whether `t` asks what's running
record Snapshot(
    Phase phase,
    String doing,
    Optional<String> below,
    int done,
    int total,
    Map<Status, Integer> counts,
    Duration elapsed,
    Estimate estimate,
    List<Optional<Worker>> workers,
    boolean keys) {
  Snapshot {
    counts = Map.copyOf(counts);
    workers = List.copyOf(workers);
  }

  int count(Status status) {
    return counts.getOrDefault(status, 0);
  }

  /// How far the mutants are by what each should take, from 0 to 1, and what's left once there's
  /// enough to tell.
  record Estimate(Optional<Double> weighted, Optional<Duration> left) {
    static final Estimate NONE = new Estimate(Optional.empty(), Optional.empty());
  }

  /// A test or a mutant a worker is running.
  ///
  /// @param id the mutant's id, empty for a test
  /// @param doing `Pricing.java:17 discount() >= becomes >`, or a test's id
  /// @param elapsed how long it has run
  /// @param expected what it should take: what its covering tests took in the coverage pass
  /// @param slow whether it has run past what it should take, as `PROGRESS.md` reckons it
  /// @param tests how many tests cover the mutant
  record Worker(
      String id, String doing, Duration elapsed, Duration expected, boolean slow, int tests) {}
}
