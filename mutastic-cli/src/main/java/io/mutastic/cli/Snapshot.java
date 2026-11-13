package io.mutastic.cli;

import io.mutastic.api.Status;
import java.util.Map;
import java.util.Optional;

/// What progress shows of a phase at one moment: what's under way and its counts.
///
/// @param doing what the phase is doing: the build read, the build tool's task, or the step after
///   the mutants
/// @param below the build tool's latest line, under the live line
/// @param done how many of `total` are done: tests in the coverage pass, mutants, or the step's
/// @param counts the mutants' results so far, by status
record Snapshot(
    Phase phase,
    String doing,
    Optional<String> below,
    int done,
    int total,
    Map<Status, Integer> counts) {
  Snapshot {
    counts = Map.copyOf(counts);
  }

  int count(Status status) {
    return counts.getOrDefault(status, 0);
  }
}
