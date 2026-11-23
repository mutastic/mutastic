package io.mutastic.engine;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Operator;
import java.lang.classfile.CodeElement;
import java.util.List;
import java.util.Optional;

/// How many calls Java 24's `Gatherers.mapConcurrent` runs at once, changed to another number above
/// zero: its results come in the order of the elements either way, so only how long it takes
/// changes, and that isn't worth a test.
///
/// Counted: the number changed to less than one, which `Gatherers` refuses as the gatherer is made,
/// so the mutant always throws and any test that runs the line catches it, as a capacity made
/// negative does; and a window's size, which changes every window.
final class GathererSizes {
  static final String CONCURRENCY = "arid: only sets how many run at once";

  private GathererSizes() {}

  /// Why the mutant `operator` makes at `code[at]`, labelled `label`, shouldn't run, if it's this.
  static Optional<String> reason(Operator operator, String label, List<CodeElement> code, int at) {
    if (operator != BuiltIn.CONSTANT_VALUE) {
      return Optional.empty();
    }
    // Only an `int` reaches `mapConcurrent`'s first parameter, so the label is a whole number.
    return Taken.of(code, at)
        .filter(GathererSizes::isTheConcurrency)
        .filter(call -> changedTo(label) >= 1)
        .map(call -> CONCURRENCY);
  }

  private static boolean isTheConcurrency(Taken call) {
    return call.is("java/util/stream/Gatherers", "mapConcurrent", 0);
  }

  /// `constant 3 to -1` gives `-1`.
  private static long changedTo(String label) {
    return Long.parseLong(label.substring(label.lastIndexOf(' ') + 1));
  }
}
