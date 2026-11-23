package io.mutastic.engine;

import io.mutastic.api.BuiltIn;
import io.mutastic.api.Operator;
import java.lang.classfile.CodeElement;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/// The numbers a gatherer of Java 24's `Gatherers` is made with that aren't worth a test:
///
/// - **a size made less than one**, a window's or `mapConcurrent`'s: `Gatherers` refuses it when
///   the gatherer is made, before any element, so the mutant always throws and any test that runs
///   the line catches it, whatever it asserts.
/// - **how many calls `mapConcurrent` runs at once**, changed to another above zero: its results
///   come in the order of the elements either way, so only how long it takes changes.
///
/// A window's size changed to another above zero is counted: it changes every window.
final class GathererSizes {
  static final String THROWS = "arid: a gatherer's size below one always throws";
  static final String CONCURRENCY = "arid: only sets how many run at once";

  private static final String GATHERERS = "java/util/stream/Gatherers";

  /// The `Gatherers` methods whose first argument is a size.
  private static final Set<String> SIZED = Set.of("windowFixed", "windowSliding", "mapConcurrent");

  private GathererSizes() {}

  /// Why the mutant `operator` makes at `code[at]`, labelled `label`, shouldn't run, if it's one of
  /// these.
  static Optional<String> reason(Operator operator, String label, List<CodeElement> code, int at) {
    if (operator != BuiltIn.CONSTANT_VALUE) {
      return Optional.empty();
    }
    return Taken.of(code, at)
        .filter(GathererSizes::isASize)
        .flatMap(call -> reason(call, changedTo(label)));
  }

  private static Optional<String> reason(Taken call, long to) {
    if (to < 1) {
      return Optional.of(THROWS);
    }
    return call.name().equals("mapConcurrent") ? Optional.of(CONCURRENCY) : Optional.empty();
  }

  private static boolean isASize(Taken call) {
    return call.owner().equals(GATHERERS) && SIZED.contains(call.name()) && call.position() == 0;
  }

  /// `constant 3 to -1` gives `-1`.
  private static long changedTo(String label) {
    return Long.parseLong(label.substring(label.lastIndexOf(' ') + 1));
  }
}
