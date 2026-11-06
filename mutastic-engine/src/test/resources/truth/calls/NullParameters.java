package calls;

import java.util.function.Supplier;

/// Each method's change, written out: `xClaim_a` is `x` as its card says the mutant for `a` makes it.
class NullParameters {
  static String both(String a, String b) {
    return a + "|" + b;
  }

  static String bothClaim_a(String a, String b) {
    a = null;
    return a + "|" + b;
  }

  static String bothClaim_b(String a, String b) {
    b = null;
    return a + "|" + b;
  }

  static int dereferenced(String a, String b) {
    return a.length() + (b == null ? 100 : 0);
  }

  static int dereferencedClaim_a(String a, String b) {
    a = null;
    return a.length() + (b == null ? 100 : 0);
  }

  static int dereferencedClaim_b(String a, String b) {
    b = null;
    return a.length() + (b == null ? 100 : 0);
  }

  // The first instruction is a loop's start: set once, on the way in, not each time round.
  static String loopsFromTheStart(String a, String b) {
    while (b.length() < 4) {
      a = a == null ? "n" : a + "x";
      b = b + "y";
    }
    return a + b;
  }

  static String loopsFromTheStartClaim_a(String a, String b) {
    a = null;
    while (b.length() < 4) {
      a = a == null ? "n" : a + "x";
      b = b + "y";
    }
    return a + b;
  }

  static String loopsFromTheStartClaim_b(String a, String b) {
    b = null;
    while (b.length() < 4) {
      a = a == null ? "n" : a + "x";
      b = b + "y";
    }
    return a + b;
  }

  String onAnInstance(String a, String b) {
    return a + "/" + b;
  }

  String onAnInstanceClaim_a(String a, String b) {
    a = null;
    return a + "/" + b;
  }

  String onAnInstanceClaim_b(String a, String b) {
    b = null;
    return a + "/" + b;
  }

  static String inALambda(String a, String b) {
    Supplier<String> joined = () -> a + "~" + b;
    return joined.get();
  }

  static String inALambdaClaim_a(String a, String b) {
    return null + "~" + b;
  }

  static String inALambdaClaim_b(String a, String b) {
    return a + "~" + null;
  }

  static String wideAndNarrowFirst(String a, String b) {
    return withPrimitives(1L, a, 2, b);
  }

  static String wideAndNarrowFirstClaim_a(String a, String b) {
    a = null;
    return withPrimitives(1L, a, 2, b);
  }

  static String wideAndNarrowFirstClaim_b(String a, String b) {
    b = null;
    return withPrimitives(1L, a, 2, b);
  }

  private static String withPrimitives(long wide, String one, int narrow, String other) {
    return wide + one + narrow + other;
  }

  // Skipped: nothing reads the value it had.

  static int neverRead(String a, String b) {
    return b.length();
  }

  static int neverReadClaim_b(String a, String b) {
    b = null;
    return b.length();
  }

  static int assignedFirst(String a, String b) {
    a = b;
    return a.length();
  }

  static int assignedFirstClaim_b(String a, String b) {
    b = null;
    a = b;
    return a.length();
  }
}
