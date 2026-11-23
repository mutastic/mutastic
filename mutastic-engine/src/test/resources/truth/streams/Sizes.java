package streams;

import java.util.List;
import java.util.stream.Gatherer;
import java.util.stream.Gatherers;
import java.util.stream.Stream;

/// The numbers a gatherer is made with, in each shape javac writes them: a window's size below one
/// always throws, and `mapConcurrent`'s number only says how many run at once; a window's size
/// above zero, and a number that isn't the size itself, are counted.
class Sizes {
  static String windowsInline(int a, int b) {
    return Stream.of(a, b, 7, 8).gather(Gatherers.windowFixed(3)).toList().toString();
  }

  static String slidingInALocal(int a, int b) {
    Gatherer<Integer, ?, List<Integer>> pairs = Gatherers.windowSliding(2);
    return Stream.of(a, b, 7).gather(pairs).toList().toString();
  }

  static String concurrently(int a, int b) {
    return Stream.of(a, b, 7).gather(Gatherers.mapConcurrent(4, x -> x * 2)).toList().toString();
  }

  static String concurrentlyCapturing(int a, int b) {
    return Stream.of(a, b).gather(Gatherers.mapConcurrent(8, x -> x + a)).toList().toString();
  }

  static String concurrentlyByReference(int a, int b) {
    List<Integer> squares = List.of(0, 1, 4, 9);
    return Stream.of(Math.floorMod(a, 4), Math.floorMod(b, 4))
        .gather(Gatherers.mapConcurrent(2, squares::get))
        .toList()
        .toString();
  }

  /// Two gatherers, one after the other.
  static String chained(int a, int b) {
    return Stream.of(a, b, 7)
        .gather(Gatherers.windowFixed(2))
        .gather(Gatherers.mapConcurrent(3, List::getFirst))
        .toList()
        .toString();
  }

  /// A number that only goes into the size: below one, it doesn't always throw.
  static String sizeFromArithmetic(int a, int b) {
    int size = Math.floorMod(b, 3) + 2;
    return Stream.of(a, b, 7).gather(Gatherers.windowFixed(size)).toList().toString();
  }

  static String sizeAtLeast(int a, int b) {
    int size = Math.max(Math.floorMod(b, 4), 2);
    return Stream.of(a, b, 7).gather(Gatherers.windowFixed(size)).toList().toString();
  }
}
