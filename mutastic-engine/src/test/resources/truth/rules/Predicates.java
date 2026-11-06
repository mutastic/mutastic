package rules;

import java.util.function.BiPredicate;
import java.util.function.IntPredicate;
import java.util.function.Predicate;
import java.util.stream.IntStream;

/// Each method's change, written out: `xClaim` is `x` as its card says the mutant makes it.
class Predicates {
  private static Predicate<String> holds(String part) {
    return text -> text.contains(part);
  }

  static boolean and(String a, String b) {
    return holds("a").and(holds(b)).test(a);
  }

  static boolean andClaim(String a, String b) {
    return holds("a").or(holds(b)).test(a);
  }

  static boolean or(String a, String b) {
    return holds("a").or(holds(b)).test(a);
  }

  static boolean orClaim(String a, String b) {
    return holds("a").and(holds(b)).test(a);
  }

  static boolean andOfTwoValues(String a, String b) {
    BiPredicate<String, String> first = String::contains;
    BiPredicate<String, String> second = String::startsWith;
    return first.and(second).test(a, b);
  }

  static boolean andOfTwoValuesClaim(String a, String b) {
    BiPredicate<String, String> first = String::contains;
    BiPredicate<String, String> second = String::startsWith;
    return first.or(second).test(a, b);
  }

  static boolean orOfInts(String a, String b) {
    IntPredicate first = value -> value > 1;
    IntPredicate second = value -> value == b.length();
    return first.or(second).test(a.length());
  }

  static boolean orOfIntsClaim(String a, String b) {
    IntPredicate first = value -> value > 1;
    IntPredicate second = value -> value == b.length();
    return first.and(second).test(a.length());
  }

  static boolean negated(String a, String b) {
    return holds(b).negate().test(a);
  }

  static boolean negatedClaim(String a, String b) {
    return holds(b).test(a);
  }

  static boolean negatedInts(String a, String b) {
    IntPredicate longer = value -> value > b.length();
    return longer.negate().test(a.length());
  }

  static boolean negatedIntsClaim(String a, String b) {
    IntPredicate longer = value -> value > b.length();
    return longer.test(a.length());
  }

  static boolean not(String a, String b) {
    return Predicate.not(holds(b)).test(a);
  }

  static boolean notClaim(String a, String b) {
    return holds(b).test(a);
  }

  static long inAFilter(String a, String b) {
    return a.chars().filter(letter -> letter != 'a').count();
  }

  static int mapped(String a, String b) {
    return IntStream.of(a.length()).map(value -> value + 1).sum();
  }

  static int mappedClaim(String a, String b) {
    return IntStream.of(a.length()).sum();
  }

  static String peeked(String a, String b) {
    var seen = new StringBuilder();
    a.chars().peek(seen::append).forEach(_ -> {});
    return seen.toString();
  }

  static String peekedClaim(String a, String b) {
    var seen = new StringBuilder();
    a.chars().forEach(_ -> {});
    return seen.toString();
  }

  static String peekedObjects(String a, String b) {
    var seen = new StringBuilder();
    java.util.stream.Stream.of(a).peek(seen::append).forEach(_ -> {});
    return seen.toString();
  }

  static String peekedObjectsClaim(String a, String b) {
    var seen = new StringBuilder();
    java.util.stream.Stream.of(a).forEach(_ -> {});
    return seen.toString();
  }

  /// `Stream.map` can change what the stream holds, so it isn't removed.
  static String mappedObjects(String a, String b) {
    return java.util.stream.Stream.of(a).map(String::length).findFirst().orElseThrow().toString();
  }
}
