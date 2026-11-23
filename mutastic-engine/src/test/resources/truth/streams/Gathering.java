package streams;

import java.util.List;
import java.util.stream.Gatherer;
import java.util.stream.Gatherers;
import java.util.stream.Stream;

/// Each method's change, written out: `xClaim` is `x` as its card says the mutant makes it, and
/// `xClaim_scan` its `scan` mutant's where a method has more than one gatherer.
class Gathering {
  static String windowsInline(int a, int b) {
    return Stream.of(a, b, 7).gather(Gatherers.windowFixed(2)).toList().toString();
  }

  static String windowsInlineClaim(int a, int b) {
    return Stream.of(a, b, 7).gather(Gatherers.windowSliding(2)).toList().toString();
  }

  static String slidingInALocal(int a, int b) {
    Gatherer<Integer, ?, List<Integer>> pairs = Gatherers.windowSliding(2);
    return Stream.of(a, b, 7).gather(pairs).toList().toString();
  }

  static String slidingInALocalClaim(int a, int b) {
    Gatherer<Integer, ?, List<Integer>> pairs = Gatherers.windowFixed(2);
    return Stream.of(a, b, 7).gather(pairs).toList().toString();
  }

  /// A size that isn't a number in the code: as many as the first input says, at least one.
  static String windowsOfAGivenSize(int a, int b) {
    int size = Math.floorMod(a, 3) + 1;
    return Stream.of(a, b, 7, 8).gather(Gatherers.windowFixed(size)).toList().toString();
  }

  static String windowsOfAGivenSizeClaim(int a, int b) {
    int size = Math.floorMod(a, 3) + 1;
    return Stream.of(a, b, 7, 8).gather(Gatherers.windowSliding(size)).toList().toString();
  }

  /// Windows of one are the same either way: skipped as equivalent.
  static String windowsOfOne(int a, int b) {
    return Stream.of(a, b, 7).gather(Gatherers.windowFixed(1)).toList().toString();
  }

  static String foldInline(int a, int b) {
    return Stream.of(a, b, 7).gather(Gatherers.fold(() -> 0, Integer::sum)).toList().toString();
  }

  static String foldInlineClaim(int a, int b) {
    return Stream.of(a, b, 7).gather(Gatherers.scan(() -> 0, Integer::sum)).toList().toString();
  }

  static String scanInALocal(int a, int b) {
    Gatherer<Integer, ?, Integer> totals = Gatherers.scan(() -> 0, (sum, each) -> sum + each);
    return Stream.of(a, b).gather(totals).toList().toString();
  }

  static String scanInALocalClaim(int a, int b) {
    Gatherer<Integer, ?, Integer> totals = Gatherers.fold(() -> 0, (sum, each) -> sum + each);
    return Stream.of(a, b).gather(totals).toList().toString();
  }

  /// Two gatherers, one after the other: the windows, then how many of them have two elements.
  static String chained(int a, int b) {
    return Stream.of(a, b, 7)
        .gather(Gatherers.windowFixed(2))
        .gather(Gatherers.fold(() -> 0, (count, window) -> count + window.size() / 2))
        .toList()
        .toString();
  }

  static String chainedClaim(int a, int b) {
    return Stream.of(a, b, 7)
        .gather(Gatherers.windowSliding(2))
        .gather(Gatherers.fold(() -> 0, (count, window) -> count + window.size() / 2))
        .toList()
        .toString();
  }

  static String chainedClaim_scan(int a, int b) {
    return Stream.of(a, b, 7)
        .gather(Gatherers.windowFixed(2))
        .gather(Gatherers.scan(() -> 0, (count, window) -> count + window.size() / 2))
        .toList()
        .toString();
  }

  /// One gatherer made of two with `andThen`.
  static String andThen(int a, int b) {
    return Stream.of(a, b, 7)
        .gather(Gatherers.<Integer>windowSliding(2).andThen(Gatherers.fold(() -> "", (s, w) -> s + w)))
        .toList()
        .toString();
  }

  static String andThenClaim(int a, int b) {
    return Stream.of(a, b, 7)
        .gather(Gatherers.<Integer>windowFixed(2).andThen(Gatherers.fold(() -> "", (s, w) -> s + w)))
        .toList()
        .toString();
  }

  static String andThenClaim_scan(int a, int b) {
    return Stream.of(a, b, 7)
        .gather(Gatherers.<Integer>windowSliding(2).andThen(Gatherers.scan(() -> "", (s, w) -> s + w)))
        .toList()
        .toString();
  }
}
