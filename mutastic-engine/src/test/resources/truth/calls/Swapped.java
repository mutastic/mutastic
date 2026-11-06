package calls;

import java.util.function.Supplier;

/// Each method's change, written out: `xClaim` is `x` as its card says the mutant makes it.
class Swapped {
  private final String first;
  private final String second;

  Swapped(String first, String second) {
    this.first = first;
    this.second = second;
  }

  Swapped(String only) {
    this(only, only);
  }

  private static int minus(int one, int other) {
    return one - other;
  }

  private static long wideMinus(long one, long other) {
    return one - other;
  }

  private static double ratio(double one, double other) {
    return (one + 1) / (other + 2);
  }

  private static String joined(String start, String one, String other) {
    return start + one + "|" + other;
  }

  private String quoted(String one, String other) {
    return first + one + "|" + other;
  }

  static int ints(String a, String b) {
    return minus(a.length(), b.length());
  }

  static int intsClaim(String a, String b) {
    return minus(b.length(), a.length());
  }

  static long longs(String a, String b) {
    return wideMinus(a.length(), b.length());
  }

  static long longsClaim(String a, String b) {
    return wideMinus(b.length(), a.length());
  }

  static double doubles(String a, String b) {
    return ratio(a.length(), b.length());
  }

  static double doublesClaim(String a, String b) {
    return ratio(b.length(), a.length());
  }

  static String references(String a, String b) {
    return a.replace(b, "x");
  }

  static String referencesClaim(String a, String b) {
    return a.replace("x", b);
  }

  static String lastTwoOfThree(String a, String b) {
    return joined("s", a, b);
  }

  static String lastTwoOfThreeClaim(String a, String b) {
    return joined("s", b, a);
  }

  static String onAnInstance(String a, String b) {
    return new Swapped("s").quoted(a, b);
  }

  static String onAnInstanceClaim(String a, String b) {
    return new Swapped("s").quoted(b, a);
  }

  static String aConstructor(String a, String b) {
    return new Swapped(a, b).first;
  }

  static String aConstructorClaim(String a, String b) {
    return new Swapped(b, a).first;
  }

  static String madeOfCalls(String a, String b) {
    return joined("s", a.trim(), b.trim());
  }

  static String madeOfCallsClaim(String a, String b) {
    return joined("s", b.trim(), a.trim());
  }

  static String aConditionalArgument(String a, String b) {
    return joined("s", a, b.isEmpty() ? a : b);
  }

  static String aConditionalArgumentClaim(String a, String b) {
    return joined("s", b.isEmpty() ? a : b, a);
  }

  static String inALambda(String a, String b) {
    Supplier<String> joining = () -> joined("s", a, b);
    return joining.get();
  }

  static String inALambdaClaim(String a, String b) {
    return joined("s", b, a);
  }

  static String aLibrarysSubstring(String a, String b) {
    return "abcdefgh".substring(a.length() % 3, 3 + b.length() % 3);
  }

  static String aLibrarysSubstringClaim(String a, String b) {
    return "abcdefgh".substring(3 + b.length() % 3, a.length() % 3);
  }

  // Skipped: the result can't change.

  static int aMax(String a, String b) {
    return Math.max(a.length(), b.length());
  }

  static int aSum(String a, String b) {
    return Integer.sum(a.length(), b.length());
  }

  static double aHypotenuse(String a, String b) {
    return Math.hypot(a.length(), b.length());
  }

  static int theSameVariableTwice(String a, String b) {
    int size = a.length() + b.length();
    return minus(size, size);
  }

  static int theSameConstantTwice(String a, String b) {
    return minus(1, 1) + a.length() + b.length();
  }

  // Left alone: not the same type, or `Object`, which a type variable compiles to, or an earlier
  // pair, which has an argument of another type above it.

  private static String withACount(String one, int count) {
    return one + count;
  }

  private static boolean sameObject(Object one, Object other) {
    return one == other;
  }

  private static String earlierPair(String one, String other, int count) {
    return one + other + count;
  }

  static String differentTypes(String a, String b) {
    return withACount(a, b.length());
  }

  static boolean objects(String a, String b) {
    return sameObject(a, b);
  }

  static String anEarlierPair(String a, String b) {
    return earlierPair(a, b, 1);
  }

  static String oneArgument(String a, String b) {
    return a.concat(b);
  }
}
