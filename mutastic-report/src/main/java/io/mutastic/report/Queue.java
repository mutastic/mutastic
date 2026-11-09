package io.mutastic.report;

import io.mutastic.api.MutantResult;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/// `mutastic next`: the missed mutants as a queue, the one most worth a test at its head, so a
/// person or an agent works through them one at a time instead of reading a list. The order is the
/// run's own ([Finding#ranked]), with the misses a baseline already knew of after the new ones;
/// `--top` spreads what it shows over the code, at most [#PER_METHOD] finding a method and
/// [#PER_FILE] a file, so one hot method can't fill it.
///
/// @param findings every missed finding, in the queue's order
/// @param legacy the ids of the missed mutants a committed baseline already knew of
public record Queue(List<Finding> findings, Set<String> legacy) {
  /// The most `--top` shows: more than a few at a time go unread.
  public static final int MOST = 25;

  /// The order's name in `--format=jsonl`: it changes only with a release.
  static final String ORDER = "run-1";

  static final int PER_METHOD = 1;
  static final int PER_FILE = 2;

  public Queue {
    findings = List.copyOf(findings);
    legacy = Set.copyOf(legacy);
  }

  /// The queue of `report`'s missed mutants: one finding a line, new before legacy, then as a run
  /// ranks them.
  public static Queue of(Report report, Set<String> legacy) {
    var missed =
        report.results().stream().filter(result -> result.status() == Status.MISSED).toList();
    var findings = Finding.ranked(Finding.of(missed, false));
    var queue = new ArrayList<Finding>();
    findings.stream().filter(finding -> !isLegacy(finding, legacy)).forEach(queue::add);
    findings.stream().filter(finding -> isLegacy(finding, legacy)).forEach(queue::add);
    return new Queue(queue, legacy);
  }

  /// A queue with nothing in it: what's left when the last `next` left nothing uncaught.
  public static Queue empty(Set<String> legacy) {
    return new Queue(List.of(), legacy);
  }

  /// A finding the baseline knew of: every mutant on it was there already.
  static boolean isLegacy(Finding finding, Set<String> legacy) {
    return finding.mutants().stream().allMatch(result -> legacy.contains(result.mutant().id()));
  }

  /// The first `count` findings, at most [#PER_METHOD] a method and [#PER_FILE] a file.
  List<Finding> top(int count) {
    var shown = new ArrayList<Finding>();
    Map<String, Integer> byMethod = new HashMap<>();
    Map<String, Integer> byFile = new HashMap<>();
    for (var finding : findings) {
      if (shown.size() == count) {
        break;
      }
      var mutant = finding.lead().mutant();
      var method = mutant.className() + "#" + mutant.methodName() + mutant.descriptor();
      var file = mutant.className().replaceFirst("\\$.*", "");
      if (byMethod.getOrDefault(method, 0) < PER_METHOD
          && byFile.getOrDefault(file, 0) < PER_FILE) {
        shown.add(finding);
        byMethod.merge(method, 1, Integer::sum);
        byFile.merge(file, 1, Integer::sum);
      }
    }
    return shown;
  }

  /// Whether nothing missed is left.
  public boolean isEmpty() {
    return findings.isEmpty();
  }

  /// How many of the queued findings the baseline knew of.
  int legacyCount() {
    return (int) findings.stream().filter(finding -> isLegacy(finding, legacy)).count();
  }

  /// How many mutants are queued.
  int mutants() {
    return Finding.mutantsIn(findings);
  }

  /// Why a finding is where it is, factor by factor, as the run ranks it: each with what it says
  /// and the value it was ranked on.
  record Factor(String signal, String why, Object value) {}

  List<Factor> factors(Finding finding) {
    var factors = new ArrayList<Factor>();
    if (!legacy.isEmpty()) {
      factors.add(
          isLegacy(finding, legacy)
              ? new Factor("baseline", "in the baseline: missed before", "legacy")
              : new Factor("baseline", "not in the baseline: a new miss", "new"));
    }
    factors.add(
        finding.prior() == 2
            ? new Factor(
                "operator",
                "a condition or a removal: it changes what the code decides or does",
                "condition-or-removal")
            : new Factor("operator", "arithmetic or another change: more often noise", "other"));
    int covering = finding.lead().coveredBy().size();
    factors.add(
        new Factor(
            "coveringTests",
            "%d test%s run%s it, and none checks it"
                .formatted(covering, covering == 1 ? "" : "s", covering == 1 ? "s" : ""),
            covering));
    return factors;
  }

  static List<MutantResult> leads(List<Finding> findings) {
    return findings.stream().map(Finding::lead).toList();
  }
}
