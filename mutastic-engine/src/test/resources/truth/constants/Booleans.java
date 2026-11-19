package constants;

import java.util.Objects;

/// Each method's change, written out: `xClaim` is `x` as its card says the mutant makes it.
class Booleans {
  private static boolean shown;

  private static int pick(boolean first, int a, int b) {
    return first ? a : b;
  }

  private static int signed(int a, boolean negative) {
    return negative ? -a : a;
  }

  static int anArgument(int a, int b) {
    return signed(a, true) + b;
  }

  static int anArgumentClaim(int a, int b) {
    return signed(a, false) + b;
  }

  static int aFirstArgument(int a, int b) {
    return pick(false, a, b);
  }

  static int aFirstArgumentClaim(int a, int b) {
    return pick(true, a, b);
  }

  static String aJdkMethodsArgument(int a, int b) {
    return String.valueOf(true) + a + b;
  }

  static String aJdkMethodsArgumentClaim(int a, int b) {
    return String.valueOf(false) + a + b;
  }

  static boolean boxed(int a, int b) {
    return Objects.equals(a > b, true);
  }

  static boolean boxedClaim(int a, int b) {
    return Objects.equals(a > b, false);
  }

  static int aField(int a, int b) {
    shown = true;
    return shown ? a : b;
  }

  static int aFieldClaim(int a, int b) {
    shown = false;
    return shown ? a : b;
  }

  static int aVariable(int a, int b) {
    boolean up = true;
    if (a > b) {
      up = false;
    }
    return up ? 1 : 0;
  }

  static int aVariableClaim_false(int a, int b) {
    boolean up = false;
    if (a > b) {
      up = false;
    }
    return up ? 1 : 0;
  }

  static int aVariableClaim_true(int a, int b) {
    boolean up = true;
    if (a > b) {
      up = true;
    }
    return up ? 1 : 0;
  }

  // Can't change the result: nothing reads the variable.

  static int neverRead(int a, int b) {
    boolean unused = true;
    return a + b;
  }

  // Not changed: returned, a condition's value, or a number.

  static boolean returned(int a, int b) {
    return true;
  }

  static boolean aConditionsValue(int a, int b) {
    boolean bigger = a > b;
    return bigger;
  }

  static int aNumber(int a, int b) {
    return Math.max(a, 1) + b;
  }
}
