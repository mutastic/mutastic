package arithmetic;

import java.util.function.IntSupplier;

/// Each method's change, written out: `xClaim` is `x` as its card says the mutant makes it.
class Increments {
  static int up(int a, int b) {
    int x = a;
    x++;
    return x;
  }

  static int upClaim(int a, int b) {
    int x = a;
    x--;
    return x;
  }

  static int down(int a, int b) {
    int x = a;
    x--;
    return x;
  }

  static int downClaim(int a, int b) {
    int x = a;
    x++;
    return x;
  }

  static int byMore(int a, int b) {
    int x = a;
    x += 5;
    return x;
  }

  static int byMoreClaim(int a, int b) {
    int x = a;
    x -= 5;
    return x;
  }

  /// Too big a step for one byte, so javac writes `wide iinc`.
  static int wide(int a, int b) {
    int x = a;
    x -= 1000;
    return x;
  }

  static int wideClaim(int a, int b) {
    int x = a;
    x += 1000;
    return x;
  }

  static int thePreviousValue(int a, int b) {
    int x = a;
    int y = x++ + b;
    return x * 3 + y;
  }

  static int thePreviousValueClaim(int a, int b) {
    int x = a;
    int y = x-- + b;
    return x * 3 + y;
  }

  static int theNextValue(int a, int b) {
    int x = a;
    int y = ++x + b;
    return x * 3 + y;
  }

  static int theNextValueClaim(int a, int b) {
    int x = a;
    int y = --x + b;
    return x * 3 + y;
  }

  static int aParameter(int a, int b) {
    a++;
    return a * b;
  }

  static int aParameterClaim(int a, int b) {
    a--;
    return a * b;
  }

  /// Bounded both ways, so the mutant ends too.
  static int aLoop(int a, int b) {
    int total = 0;
    for (int i = 0; i < 3 && i > -3; i++) {
      total += i * a + 1;
    }
    return total;
  }

  static int aLoopClaim(int a, int b) {
    int total = 0;
    for (int i = 0; i < 3 && i > -3; i--) {
      total += i * a + 1;
    }
    return total;
  }

  static int aWhile(int a, int b) {
    int i = 0;
    while (i < 2 && i > -2) {
      i += 1;
    }
    return i + a;
  }

  static int aWhileClaim(int a, int b) {
    int i = 0;
    while (i < 2 && i > -2) {
      i -= 1;
    }
    return i + a;
  }

  int onAnInstance(int a, int b) {
    int x = b;
    x++;
    return x;
  }

  int onAnInstanceClaim(int a, int b) {
    int x = b;
    x--;
    return x;
  }

  static int inALambda(int a, int b) {
    IntSupplier stepped =
        () -> {
          int x = a;
          x++;
          return x;
        };
    return stepped.getAsInt();
  }

  static int inALambdaClaim(int a, int b) {
    IntSupplier stepped =
        () -> {
          int x = a;
          x--;
          return x;
        };
    return stepped.getAsInt();
  }

  /// One change to every copy of the `finally`.
  static int inAFinally(int a, int b) {
    int x = a;
    try {
      if (b == 0) {
        throw new IllegalStateException("zero");
      }
    } catch (IllegalStateException e) {
      return x;
    } finally {
      x++;
    }
    return x;
  }

  static int inAFinallyClaim(int a, int b) {
    int x = a;
    try {
      if (b == 0) {
        throw new IllegalStateException("zero");
      }
    } catch (IllegalStateException e) {
      return x;
    } finally {
      x--;
    }
    return x;
  }

  static int inSwitch(int a, int b) {
    int x = b;
    switch (a) {
      case 1:
        x++;
        break;
      default:
        x = 7;
    }
    return x;
  }

  static int inSwitchClaim(int a, int b) {
    int x = b;
    switch (a) {
      case 1:
        x--;
        break;
      default:
        x = 7;
    }
    return x;
  }

  // Skipped as equivalent.

  static int neverRead(int a, int b) {
    int x = a;
    x++;
    return b;
  }

  static int overwritten(int a, int b) {
    int x = a;
    x++;
    x = b;
    return x;
  }

  // No step of a source variable to change.

  /// javac's own index, which no source names.
  static int enhancedFor(int a, int b) {
    int total = 0;
    for (int value : new int[] {a, b}) {
      total += value;
    }
    return total;
  }

  static long aLong(int a, int b) {
    long x = a;
    x++;
    return x;
  }

  static byte aByte(int a, int b) {
    byte x = (byte) a;
    x++;
    return x;
  }

  static int writtenOut(int a, int b) {
    int x = a;
    x = x + 1;
    return x;
  }

  static int byAVariable(int a, int b) {
    int x = a;
    x += b;
    return x;
  }

  static int byNothing(int a, int b) {
    int x = a;
    x += 0;
    return x;
  }

  static int theSmallestStep(int a, int b) {
    int x = a;
    x -= 32768;
    return x;
  }
}
