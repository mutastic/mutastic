package io.mutastic.api;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/// A test the run named likely flaky, and what came of it: none of its catches count in this run,
/// as each mutant it reached ran again without it (decision 27).
///
/// @param byReason what named it, as the events say it, in this order: `changed-since-last-run`,
///   `catch-not-repeated`, `fails-alone`; each with the mutants whose results it changed that way:
///   a catch whose result changed since the last run with the same code, a catch that didn't
///   repeat, or, when it failed on its own, every mutant it caught
/// @param rerun the mutants it reached that ran again without it
@Internal
public record LikelyFlaky(String test, Map<String, List<String>> byReason, List<String> rerun) {
  public static final String CHANGED = "changed-since-last-run";
  public static final String NOT_REPEATED = "catch-not-repeated";
  public static final String FAILS_ALONE = "fails-alone";

  /// The reasons, in the order the events list them.
  private static final List<String> ORDER = List.of(CHANGED, NOT_REPEATED, FAILS_ALONE);

  public LikelyFlaky {
    var ordered = new LinkedHashMap<String, List<String>>();
    for (String reason : ORDER) {
      if (byReason.containsKey(reason)) {
        ordered.put(reason, List.copyOf(new TreeSet<>(byReason.get(reason))));
      }
    }
    byReason = java.util.Collections.unmodifiableMap(ordered);
    rerun = List.copyOf(new TreeSet<>(rerun));
  }

  /// What named it, in order.
  public List<String> reasons() {
    return List.copyOf(byReason.keySet());
  }

  /// Every mutant whose result it changed, whatever the reason, sorted.
  public List<String> mutants() {
    var all = new TreeSet<String>();
    byReason.values().forEach(all::addAll);
    return List.copyOf(all);
  }

  /// The mutants it changed for `reason`: none when that didn't name it.
  public List<String> mutants(String reason) {
    return byReason.getOrDefault(reason, List.of());
  }

  /// Whether only failing on its own named it, which the run says in a line of its own.
  public boolean onlyFailsAlone() {
    return byReason.keySet().equals(java.util.Set.of(FAILS_ALONE));
  }
}
