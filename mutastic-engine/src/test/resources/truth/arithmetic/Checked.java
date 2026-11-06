package arithmetic;

import java.util.function.IntSupplier;

/// Each method's change, written out: `xClaim` is `x` as its card says the mutant makes it. The
/// fixture's inputs reach both ends of `int`, so each overflows for some of them, and a mutant
/// that changes nothing fails: every card's "no longer throws" has been seen to happen.
class Checked {
  /// A `long` at the end of its range for one input, so the `int` inputs can overflow it.
  private static long big(int a) {
    return a == Integer.MAX_VALUE
        ? Long.MAX_VALUE
        : a == Integer.MIN_VALUE ? Long.MIN_VALUE : a;
  }

  static int anAdd(int a, int b) {
    return Math.addExact(a, b);
  }

  static int anAddClaim(int a, int b) {
    return a + b;
  }

  static int aSubtract(int a, int b) {
    return Math.subtractExact(a, b);
  }

  static int aSubtractClaim(int a, int b) {
    return a - b;
  }

  static int aMultiply(int a, int b) {
    return Math.multiplyExact(a, b);
  }

  static int aMultiplyClaim(int a, int b) {
    return a * b;
  }

  static int anIncrement(int a, int b) {
    return Math.incrementExact(a);
  }

  static int anIncrementClaim(int a, int b) {
    return a + 1;
  }

  static int aDecrement(int a, int b) {
    return Math.decrementExact(a);
  }

  static int aDecrementClaim(int a, int b) {
    return a - 1;
  }

  static int aNegate(int a, int b) {
    return Math.negateExact(a);
  }

  static int aNegateClaim(int a, int b) {
    return -a;
  }

  static long aLongAdd(int a, int b) {
    return Math.addExact(big(a), (long) b);
  }

  static long aLongAddClaim(int a, int b) {
    return big(a) + (long) b;
  }

  static long aLongSubtract(int a, int b) {
    return Math.subtractExact(big(a), (long) b);
  }

  static long aLongSubtractClaim(int a, int b) {
    return big(a) - (long) b;
  }

  static long aLongMultiply(int a, int b) {
    return Math.multiplyExact(big(a), (long) b);
  }

  static long aLongMultiplyClaim(int a, int b) {
    return big(a) * (long) b;
  }

  static long aLongTimesAnInt(int a, int b) {
    return Math.multiplyExact(big(a), b);
  }

  static long aLongTimesAnIntClaim(int a, int b) {
    return big(a) * b;
  }

  static long aLongIncrement(int a, int b) {
    return Math.incrementExact(big(a));
  }

  static long aLongIncrementClaim(int a, int b) {
    return big(a) + 1L;
  }

  static long aLongDecrement(int a, int b) {
    return Math.decrementExact(big(a));
  }

  static long aLongDecrementClaim(int a, int b) {
    return big(a) - 1L;
  }

  static long aLongNegate(int a, int b) {
    return Math.negateExact(big(a));
  }

  static long aLongNegateClaim(int a, int b) {
    return -big(a);
  }

  static int toAnInt(int a, int b) {
    return Math.toIntExact(big(a));
  }

  static int toAnIntClaim(int a, int b) {
    return (int) big(a);
  }

  static int onStrictMath(int a, int b) {
    return StrictMath.addExact(a, b);
  }

  static int onStrictMathClaim(int a, int b) {
    return a + b;
  }

  static int fromAWiderSum(int a, int b) {
    return Math.toIntExact((long) a + b);
  }

  static int fromAWiderSumClaim(int a, int b) {
    return (int) ((long) a + b);
  }

  static int inALambda(int a, int b) {
    IntSupplier sum = () -> Math.addExact(a, b);
    return sum.getAsInt();
  }

  static int inALambdaClaim(int a, int b) {
    IntSupplier sum = () -> a + b;
    return sum.getAsInt();
  }

  static String caught(int a, int b) {
    try {
      return "fits " + Math.addExact(a, b);
    } catch (ArithmeticException e) {
      return "too big";
    }
  }

  static String caughtClaim(int a, int b) {
    try {
      return "fits " + (a + b);
    } catch (ArithmeticException e) {
      return "too big";
    }
  }

  // Not what the operator changes.

  static int anAbsolute(int a, int b) {
    return Math.abs(a);
  }

  static int aFloorDivision(int a, int b) {
    return Math.floorDiv(a, 2);
  }
}
