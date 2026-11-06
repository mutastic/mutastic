package rules;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;
import java.util.OptionalLong;

/// Each method's change, written out: `xClaim` is `x` as its card says the mutant makes it. The
/// `Optional`s these use come from helpers, whose own changes their callers show: `found` has
/// a value where it's told there is, as do the primitive forms.
class Optionals {
  private static Optional<String> found(String a, boolean there) {
    return there ? Optional.of(a) : Optional.empty();
  }

  private static OptionalInt anInt(String a, boolean there) {
    return there ? OptionalInt.of(a.length() + 1) : OptionalInt.empty();
  }

  private static OptionalLong aLong(String a, boolean there) {
    return there ? OptionalLong.of(a.length() + 1) : OptionalLong.empty();
  }

  private static int number(String a) {
    return a.length();
  }

  static boolean present(String a, String b) {
    return found(a, a.contains(b)).isPresent();
  }

  static boolean presentClaim(String a, String b) {
    return found(a, a.contains(b)).isEmpty();
  }

  static boolean empty(String a, String b) {
    return found(a, a.contains(b)).isEmpty();
  }

  static boolean emptyClaim(String a, String b) {
    return found(a, a.contains(b)).isPresent();
  }

  static String inACondition(String a, String b) {
    var value = found(a, a.contains(b));
    if (value.isPresent()) {
      return value.get();
    }
    return b;
  }

  static String inAConditionClaim(String a, String b) {
    var value = found(a, a.contains(b));
    if (value.isEmpty()) {
      return value.get();
    }
    return b;
  }

  static boolean primitivePresent(String a, String b) {
    return anInt(a, a.contains(b)).isPresent();
  }

  static boolean primitivePresentClaim(String a, String b) {
    return anInt(a, a.contains(b)).isEmpty();
  }

  static String ofAValue(String a, String b) {
    return Optional.of(a).orElseThrow();
  }

  static String ofAValueClaim(String a, String b) {
    return Optional.<String>empty().orElseThrow();
  }

  static String ofNullable(String a, String b) {
    return Optional.ofNullable(a.contains(b) ? a : null).orElseThrow();
  }

  static String ofNullableClaim(String a, String b) {
    return Optional.<String>empty().orElseThrow();
  }

  static int ofAnInt(String a, String b) {
    return OptionalInt.of(number(a)).orElseThrow();
  }

  static int ofAnIntClaim(String a, String b) {
    return OptionalInt.empty().orElseThrow();
  }

  static long ofALong(String a, String b) {
    return OptionalLong.of(number(a)).orElseThrow();
  }

  static long ofALongClaim(String a, String b) {
    return OptionalLong.empty().orElseThrow();
  }

  static double ofADouble(String a, String b) {
    return OptionalDouble.of(number(a)).orElseThrow();
  }

  static double ofADoubleClaim(String a, String b) {
    return OptionalDouble.empty().orElseThrow();
  }

  static String filtered(String a, String b) {
    return found(a, a.contains(b)).filter(value -> value.length() > 1).orElseThrow();
  }

  static String filteredClaim(String a, String b) {
    return found(a, a.contains(b)).orElseThrow();
  }

  static String orElse(String a, String b) {
    return found(a, a.contains(b)).orElse("none");
  }

  static String orElseClaim_argument(String a, String b) {
    return "none";
  }

  static String orElseClaim(String a, String b) {
    return found(a, a.contains(b)).get();
  }

  static String orElseGet(String a, String b) {
    return found(a, a.contains(b)).orElseGet(() -> a + b);
  }

  static String orElseGetClaim(String a, String b) {
    return found(a, a.contains(b)).get();
  }

  static int orElseOfAnInt(String a, String b) {
    return anInt(a, a.contains(b)).orElse(a.length());
  }

  static int orElseOfAnIntClaim(String a, String b) {
    return a.length();
  }

  static long orElseOfALong(String a, String b) {
    return aLong(a, a.contains(b)).orElse(a.length());
  }

  static long orElseOfALongClaim(String a, String b) {
    return a.length();
  }

  static boolean aVoidCall(String a, String b) {
    var seen = new boolean[1];
    found(a, a.contains(b)).ifPresent(value -> seen[0] = true);
    return seen[0];
  }
}
