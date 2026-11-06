package arithmetic;

import java.util.function.IntSupplier;

/// Each method's change, written out: `xClaim` is `x` as its card says the mutant makes it.
class Negations {
  static int anInt(int a, int b) {
    return -a;
  }

  static int anIntClaim(int a, int b) {
    return a;
  }

  static long aLong(int a, int b) {
    long x = a;
    return -x + 1;
  }

  static long aLongClaim(int a, int b) {
    long x = a;
    return x + 1;
  }

  static float aFloat(int a, int b) {
    float x = a;
    return -x;
  }

  static float aFloatClaim(int a, int b) {
    float x = a;
    return x;
  }

  static double aDouble(int a, int b) {
    double x = a / 2.0;
    return -x;
  }

  static double aDoubleClaim(int a, int b) {
    double x = a / 2.0;
    return x;
  }

  static int aSum(int a, int b) {
    return -(a + b);
  }

  static int aSumClaim(int a, int b) {
    return a + b;
  }

  static int anOperand(int a, int b) {
    return a * -b;
  }

  static int anOperandClaim(int a, int b) {
    return a * b;
  }

  static int aCallsResult(int a, int b) {
    return -Math.abs(a);
  }

  static int aCallsResultClaim(int a, int b) {
    return Math.abs(a);
  }

  static boolean inACondition(int a, int b) {
    return -a > b;
  }

  static boolean inAConditionClaim(int a, int b) {
    return a > b;
  }

  static int onItself(int a, int b) {
    int x = a;
    x = -x;
    return x + b;
  }

  static int onItselfClaim(int a, int b) {
    int x = a;
    x = x;
    return x + b;
  }

  static int inATernary(int a, int b) {
    return a < 0 ? -a : a;
  }

  static int inATernaryClaim(int a, int b) {
    return a < 0 ? a : a;
  }

  static int inALambda(int a, int b) {
    IntSupplier negated = () -> -a;
    return negated.getAsInt();
  }

  static int inALambdaClaim(int a, int b) {
    IntSupplier negated = () -> a;
    return negated.getAsInt();
  }

  // No negation in the code.

  static int aNegativeLiteral(int a, int b) {
    return a + -5;
  }

  private static final int LIMIT = 7;

  static int aNegatedConstant(int a, int b) {
    return a + -LIMIT;
  }

  static int aSubtraction(int a, int b) {
    return a - b;
  }

  static int aComplement(int a, int b) {
    return ~a;
  }
}
