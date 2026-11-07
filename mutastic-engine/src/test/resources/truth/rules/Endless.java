package rules;

import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/// `limit` on streams that do and don't end without it. A source burns a fuse, so that a mutant
/// without its limit says so by throwing, rather than hanging the test.
class Endless {
  private static final int FUSE = 1_000;

  static int fused(int pulled) {
    if (pulled > FUSE) {
      throw new IllegalStateException("never ended");
    }
    return pulled;
  }

  // All that ends each of these is the limit.

  static String iterated(String a, String b) {
    return Stream.iterate(0, n -> fused(n + 1)).limit(3).toList().toString();
  }

  static String generated(String a, String b) {
    var pulled = new int[1];
    return Stream.generate(() -> fused(pulled[0]++)).limit(3).toList().toString();
  }

  static int intsIterated(String a, String b) {
    return IntStream.iterate(0, n -> fused(n + 1)).limit(3).sum();
  }

  static int intsGenerated(String a, String b) {
    var pulled = new int[1];
    return IntStream.generate(() -> fused(pulled[0]++)).limit(3).sum();
  }

  static long longsIterated(String a, String b) {
    return LongStream.iterate(0, n -> fused((int) n + 1)).limit(3).sum();
  }

  static long longsGenerated(String a, String b) {
    var pulled = new int[1];
    return LongStream.generate(() -> fused(pulled[0]++)).limit(3).sum();
  }

  static double doublesIterated(String a, String b) {
    return DoubleStream.iterate(0, n -> fused((int) n + 1)).limit(3).sum();
  }

  static double doublesGenerated(String a, String b) {
    var pulled = new int[1];
    return DoubleStream.generate(() -> fused(pulled[0]++)).limit(3).sum();
  }

  static String throughAStage(String a, String b) {
    return Stream.iterate(0, n -> fused(n + 1)).map(n -> n * 2).limit(3).toList().toString();
  }

  static String throughATypeChange(String a, String b) {
    return IntStream.iterate(0, n -> fused(n + 1)).boxed().limit(3).toList().toString();
  }

  static String throughAParallelStage(String a, String b) {
    return Stream.iterate(0, n -> fused(n + 1)).parallel().limit(3).toList().toString();
  }

  static String throughAVariable(String a, String b) {
    var numbers = Stream.iterate(0, n -> fused(n + 1));
    return numbers.limit(3).toList().toString();
  }

  static String aLimitWorkedOut(String a, String b) {
    return Stream.iterate(0, n -> fused(n + 1)).limit(a.length()).toList().toString();
  }

  static String stagesAfterIt(String a, String b) {
    return Stream.iterate(0, n -> fused(n + 1)).limit(3).map(n -> n * 2).toList().toString();
  }

  static Stream<Integer> handedToTheCaller(String a, String b) {
    return Stream.iterate(0, n -> fused(n + 1)).limit(3);
  }

  // Each of these ends without the limit, so its mutant shows something.

  static String bounded(String a, String b) {
    return Stream.iterate(0, n -> n < 5, n -> n + 1).limit(2).toList().toString();
  }

  static String boundedClaim(String a, String b) {
    return Stream.iterate(0, n -> n < 5, n -> n + 1).toList().toString();
  }

  static int intsBounded(String a, String b) {
    return IntStream.iterate(0, n -> n < 5, n -> n + 1).limit(2).sum();
  }

  static int intsBoundedClaim(String a, String b) {
    return IntStream.iterate(0, n -> n < 5, n -> n + 1).sum();
  }

  static int notFromHere(String a, String b) {
    return a.chars().limit(1).sum();
  }

  static int notFromHereClaim(String a, String b) {
    return a.chars().sum();
  }

  static boolean matchedWithoutIt(String a, String b) {
    return Stream.iterate(0, n -> fused(n + 1)).limit(3).anyMatch(n -> n > 5);
  }

  static boolean matchedWithoutItClaim(String a, String b) {
    return Stream.iterate(0, n -> fused(n + 1)).anyMatch(n -> n > 5);
  }

  // Ended some other way too, where what the limit changes isn't what's asked here.

  static String limitedBefore(String a, String b) {
    return Stream.iterate(0, n -> fused(n + 1)).limit(4).map(n -> n * 2).limit(2).toList().toString();
  }

  static String endedBefore(String a, String b) {
    return Stream.iterate(0, n -> fused(n + 1)).takeWhile(n -> n < 9).limit(2).toList().toString();
  }

  static String endedAfter(String a, String b) {
    return Stream.iterate(0, n -> fused(n + 1)).limit(9).takeWhile(n -> n < 2).toList().toString();
  }

  static String foundAfter(String a, String b) {
    return Stream.iterate(0, n -> fused(n + 1)).limit(9).filter(n -> n > 2).findFirst().toString();
  }

  static String twoValues(String a, String b) {
    var numbers = Stream.iterate(0, n -> fused(n + 1));
    numbers = numbers.map(n -> n + 1);
    return numbers.limit(2).toList().toString();
  }

  static String aParameter(Stream<Integer> numbers) {
    return numbers.limit(2).toList().toString();
  }
}
