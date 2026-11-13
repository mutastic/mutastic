package io.mutastic.report;

import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.util.List;
import java.util.Set;

/// `run --fail-on-new`: a run judged on the misses a committed baseline doesn't hold, not on its
/// score, for a project whose score can't reach the threshold yet. A mutant no test catches, missed
/// or reached by none, is what a baseline holds ([#uncaught]); one the baseline doesn't hold is
/// new, and the run passes while there are none. As with a score, a test that caught mutants and
/// fails on its own fails the run: its catches can't be trusted.
///
/// @param baseline the ids in the project's baseline
public record FailOnNew(Set<String> baseline) {
  public FailOnNew {
    baseline = Set.copyOf(baseline);
  }

  /// A mutant no test catches, missed or reached by none: what a baseline holds.
  public static boolean uncaught(MutantResult result) {
    return result.status() == Status.MISSED || result.status() == Status.NOT_COVERED;
  }

  /// The ids of the mutants no test catches that the baseline doesn't hold, in the run's order.
  public List<String> newMisses(Report report) {
    return report.results().stream()
        .filter(FailOnNew::uncaught)
        .map(result -> result.mutant().id())
        .filter(id -> !baseline.contains(id))
        .toList();
  }

  /// How many of the mutants no test catches the baseline holds: missed before, and still missed.
  public int inBaseline(Report report) {
    return (int)
        report.results().stream()
            .filter(FailOnNew::uncaught)
            .filter(result -> baseline.contains(result.mutant().id()))
            .count();
  }

  /// No new miss, and no test that caught mutants failing on its own.
  public boolean passes(Report report) {
    return report.failingAlone().isEmpty() && newMisses(report).isEmpty();
  }
}
