package library;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;

/// Each method's change, written out: `xClaim` is `x` as its card says the mutant makes it.
class Swaps {
  static int indexOf(String a, String b) {
    return a.indexOf(b);
  }

  static int indexOfClaim(String a, String b) {
    return a.lastIndexOf(b);
  }

  static int indexOfChar(String a, String b) {
    return a.indexOf(b.charAt(0));
  }

  static int indexOfCharClaim(String a, String b) {
    return a.lastIndexOf(b.charAt(0));
  }

  static int lastIndexOf(String a, String b) {
    return a.lastIndexOf(b);
  }

  static int lastIndexOfClaim(String a, String b) {
    return a.indexOf(b);
  }

  static int foundAt(String a, String b) {
    int at = a.indexOf(b);
    return at + 1;
  }

  static int foundAtClaim(String a, String b) {
    int at = a.lastIndexOf(b);
    return at + 1;
  }

  static boolean foundAfterTheStart(String a, String b) {
    return a.indexOf(b) > 0;
  }

  static boolean foundAfterTheStartClaim(String a, String b) {
    return a.lastIndexOf(b) > 0;
  }

  static boolean foundAtTheStart(String a, String b) {
    return a.indexOf(b) == 0;
  }

  static boolean foundAtTheStartClaim(String a, String b) {
    return a.lastIndexOf(b) == 0;
  }

  static int inALambda(String a, String b) {
    Supplier<Integer> search = () -> a.indexOf(b);
    return search.get();
  }

  static int inALambdaClaim(String a, String b) {
    return a.lastIndexOf(b);
  }

  static boolean startsWith(String a, String b) {
    return a.startsWith(b);
  }

  static boolean startsWithClaim(String a, String b) {
    return a.endsWith(b);
  }

  static int endsWithInACondition(String a, String b) {
    if (a.endsWith(b)) {
      return 1;
    }
    return 0;
  }

  static int endsWithInAConditionClaim(String a, String b) {
    if (a.startsWith(b)) {
      return 1;
    }
    return 0;
  }

  static String toUpperCase(String a, String b) {
    return a.toUpperCase() + b;
  }

  static String toUpperCaseClaim(String a, String b) {
    return a.toLowerCase() + b;
  }

  static String toLowerCaseWithALocale(String a, String b) {
    return a.toLowerCase(Locale.ROOT) + b;
  }

  static String toLowerCaseWithALocaleClaim(String a, String b) {
    return a.toUpperCase(Locale.ROOT) + b;
  }

  static String stripLeading(String a, String b) {
    return a.stripLeading() + b;
  }

  static String stripLeadingClaim(String a, String b) {
    return a.stripTrailing() + b;
  }

  static String stripTrailing(String a, String b) {
    return a.stripTrailing() + b;
  }

  static String stripTrailingClaim(String a, String b) {
    return a.stripLeading() + b;
  }

  static int minOfInts(String a, String b) {
    return Math.min(a.length(), b.length());
  }

  static int minOfIntsClaim(String a, String b) {
    return Math.max(a.length(), b.length());
  }

  static long maxOfLongs(String a, String b) {
    return Math.max((long) a.length(), (long) b.length());
  }

  static long maxOfLongsClaim(String a, String b) {
    return Math.min((long) a.length(), (long) b.length());
  }

  static double minOfDoubles(String a, String b) {
    return Math.min(a.length() / 2.0, b.length() / 2.0);
  }

  static double minOfDoublesClaim(String a, String b) {
    return Math.max(a.length() / 2.0, b.length() / 2.0);
  }

  static float maxOfFloats(String a, String b) {
    return Math.max(a.length() / 2f, b.length() / 2f);
  }

  static float maxOfFloatsClaim(String a, String b) {
    return Math.min(a.length() / 2f, b.length() / 2f);
  }

  static double floor(String a, String b) {
    return Math.floor(a.length() / 2.0) + b.length();
  }

  static double floorClaim(String a, String b) {
    return Math.ceil(a.length() / 2.0) + b.length();
  }

  static double ceil(String a, String b) {
    return Math.ceil(a.length() / 2.0) + b.length();
  }

  static double ceilClaim(String a, String b) {
    return Math.floor(a.length() / 2.0) + b.length();
  }

  static String minOfAStream(String a, String b) {
    return Stream.of(a, b).min(Comparator.naturalOrder()).orElseThrow();
  }

  static String minOfAStreamClaim(String a, String b) {
    return Stream.of(a, b).max(Comparator.naturalOrder()).orElseThrow();
  }

  static String longestInAStream(String a, String b) {
    return Stream.of(a, b).max(Comparator.comparing(String::length)).orElseThrow();
  }

  static String longestInAStreamClaim(String a, String b) {
    return Stream.of(a, b).min(Comparator.comparing(String::length)).orElseThrow();
  }

  static String maxOfACollection(String a, String b) {
    return Collections.max(List.of(a, b));
  }

  static String maxOfACollectionClaim(String a, String b) {
    return Collections.min(List.of(a, b));
  }

  static String shortestInACollection(String a, String b) {
    return Collections.min(List.of(a, b), Comparator.comparing(String::length));
  }

  static String shortestInACollectionClaim(String a, String b) {
    return Collections.max(List.of(a, b), Comparator.comparing(String::length));
  }

  static int minOfAnIntStream(String a, String b) {
    return IntStream.of(a.length(), b.length()).min().orElseThrow();
  }

  static int minOfAnIntStreamClaim(String a, String b) {
    return IntStream.of(a.length(), b.length()).max().orElseThrow();
  }

  static long maxOfALongStream(String a, String b) {
    return LongStream.of(a.length(), b.length()).max().orElseThrow();
  }

  static long maxOfALongStreamClaim(String a, String b) {
    return LongStream.of(a.length(), b.length()).min().orElseThrow();
  }

  static double minOfADoubleStream(String a, String b) {
    return DoubleStream.of(a.length() / 2.0, b.length() / 2.0).min().orElseThrow();
  }

  static double minOfADoubleStreamClaim(String a, String b) {
    return DoubleStream.of(a.length() / 2.0, b.length() / 2.0).max().orElseThrow();
  }

  static String minBy(String a, String b) {
    return Stream.of(a, b).collect(Collectors.minBy(Comparator.naturalOrder())).orElseThrow();
  }

  static String minByClaim(String a, String b) {
    return Stream.of(a, b).collect(Collectors.maxBy(Comparator.naturalOrder())).orElseThrow();
  }

  static String maxBy(String a, String b) {
    return Stream.of(a, b).collect(Collectors.maxBy(Comparator.naturalOrder())).orElseThrow();
  }

  static String maxByClaim(String a, String b) {
    return Stream.of(a, b).collect(Collectors.minBy(Comparator.naturalOrder())).orElseThrow();
  }

  // Can't change the result: both agree on whether it's there.

  static boolean isThere(String a, String b) {
    return a.indexOf(b) >= 0;
  }

  static boolean isNotThere(String a, String b) {
    return a.lastIndexOf(b) < 0;
  }

  static boolean isMinusOne(String a, String b) {
    return a.indexOf(b) == -1;
  }

  static boolean isNotMinusOne(String a, String b) {
    return a.indexOf(b.charAt(0)) != -1;
  }

  static int overMinusOne(String a, String b) {
    if (a.lastIndexOf(b) > -1) {
      return 1;
    }
    return 0;
  }

  static boolean atMostMinusOne(String a, String b) {
    return a.indexOf(b) <= -1;
  }

  // Can't change the result: a whole number rounds to itself.

  static double floorOfAnInt(String a, String b) {
    return Math.floor((double) a.length()) + b.length();
  }

  static double ceilOfADivision(String a, String b) {
    return Math.ceil(a.length() / 2) + b.length();
  }

  static double floorOfALong(String a, String b) {
    return Math.floor((double) (long) a.length()) + b.length();
  }

  // Not swapped: no such overload of the other method, or it searches from a starting point.

  static boolean startsWithAtAnOffset(String a, String b) {
    return a.startsWith(b, 1);
  }

  static int indexOfFrom(String a, String b) {
    return a.indexOf(b, 1);
  }

  static int lastIndexOfFrom(String a, String b) {
    return a.lastIndexOf(b, 1);
  }

  static int indexOfBetween(String a, String b) {
    return a.indexOf(b.charAt(0), 0, a.length());
  }

  static String trimmed(String a, String b) {
    return a.trim() + b.toString();
  }
}
