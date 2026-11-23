package io.mutastic.report;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/// A test a run was told to leave out: one under `exclude-tests:` in `mutastic.yaml`, with the
/// reason it's there, or one `--exclude-tests` adds for a run, without one. Neither runs: what only
/// it reaches counts as not covered, so the score can only fall, and the summary names each.
///
/// @param test as it was given: a test class by its name or full name, or `PricingTest#bigOrders`
/// @param reason why, from `mutastic.yaml`; empty for one given on the command line
public record LeftOut(String test, Optional<String> reason) {
  /// The tests a run leaves out: `configured`'s first, then each of `given` not already there.
  public static List<LeftOut> of(List<LeftOut> configured, List<String> given) {
    var all = new ArrayList<>(configured);
    var named = new LinkedHashSet<String>(names(configured));
    for (String test : given) {
      if (named.add(test)) {
        all.add(new LeftOut(test, Optional.empty()));
      }
    }
    return List.copyOf(all);
  }

  /// Each test's name, in order.
  public static List<String> names(List<LeftOut> leftOut) {
    return leftOut.stream().map(LeftOut::test).toList();
  }
}
