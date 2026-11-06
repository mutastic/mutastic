package stores;

import java.util.List;
import java.util.function.IntSupplier;

/// One method for each shape javac compiles an assignment to a local variable to. Each assignment
/// is on a line of its own, so the card's claim, the assignment removed, can be written by taking
/// the line out, or, where its value is used, by the line in its `// without NAME:` comment.
@SuppressWarnings({"unused", "UnusedAssignment"})
final class Assignments {
  private Assignments() {}

  // Re-assignments of every kind of value.

  static int anInt(int a, int b) {
    int x = a;
    x = b + 1;
    return x;
  }

  static long aLong(int a, int b) {
    long x = a;
    x = b * 3L;
    return x;
  }

  static float aFloat(int a, int b) {
    float x = a;
    x = b / 2f;
    return x;
  }

  static double aDouble(int a, int b) {
    double x = a;
    x = b / 2.0;
    return x;
  }

  static String aReference(int a, int b) {
    String x = "a" + a;
    x = "b" + b;
    return x;
  }

  static String fromNull(int a, int b) {
    String x = null;
    x = "b" + b;
    return String.valueOf(x);
  }

  static List<Integer> narrowedByItsValue(int a, int b) {
    List<Integer> x = List.of(a);
    x = new java.util.ArrayList<>(List.of(b));
    return x;
  }

  // Where it's written.

  static int aParameter(int a, int b) {
    a = a + b + 1;
    return a;
  }

  static int compound(int a, int b) {
    int x = a;
    x += b + 1;
    return x;
  }

  static int onABranch(int a, int b) {
    int x = 0;
    if (a > b) {
      x = a + 1;
    }
    return x;
  }

  static int inALoop(int a, int b) {
    int total = 1;
    for (int i = 0; i < a; i++) {
      total = total + b + 1;
    }
    return total;
  }

  static int inASwitch(int a, int b) {
    int x = 7;
    switch (a) {
      case 1:
        x = b;
        break;
      case 2:
        return x;
      default:
        break;
    }
    return x;
  }

  static int inATry(int a, int b) {
    int x = a;
    try {
      x = 10 / b;
    } catch (ArithmeticException e) {
      return x;
    }
    return x;
  }

  static int readByTheHandler(int a, int b) {
    int x = a;
    try {
      x = b + 100;
      x = 10 / b;
      return x;
    } catch (ArithmeticException e) {
      return x;
    }
  }

  static int inAFinally(int a, int b) {
    int x = a;
    try {
      if (b < 0) {
        return a / (b + 1);
      }
    } finally {
      x = b + 1;
    }
    return x;
  }

  static int inALambda(int a, int b) {
    IntSupplier body =
        () -> {
          int x = a;
          x = b + 1;
          return x;
        };
    return body.getAsInt();
  }

  // Where its value is used as well.

  static int inAnExpression(int a, int b) {
    int x = a;
    int y = (x = b + 1) + 1; // without x: int y = (b + 1) + 1;
    return x * 100 + y;
  }

  static int chained(int a, int b) {
    int x = a;
    int y = a;
    x = y = b + 1; // without x: y = b + 1; // without y: x = b + 1;
    return x * 100 + y;
  }

  // Not re-assignments: nothing was there before.

  static int declared(int a, int b) {
    int x = a + b;
    return x;
  }

  static int declaredThenAssigned(int a, int b) {
    int x;
    if (a > b) {
      x = a;
    } else {
      x = b;
    }
    return x;
  }

  static int slotUsedAgain(int a, int b) {
    {
      int p = a;
      if (p > 100) {
        return p;
      }
    }
    {
      int q = b;
      return q;
    }
  }

  static int forEach(int a, int b) {
    for (int value : new int[] {a, b}) {
      if (value > 100) {
        return value;
      }
    }
    return 0;
  }

  static String caught(int a, int b) {
    try {
      return String.valueOf(a / b);
    } catch (ArithmeticException e) {
      return e.getMessage();
    }
  }

  static int pattern(int a, int b) {
    Object boxed = a;
    return boxed instanceof Integer number ? number + b : b;
  }

  static int stringSwitch(int a, int b) {
    switch (Integer.toString(a)) {
      case "1":
        return b;
      default:
        return a;
    }
  }

  static int stringSwitchInALambda(int a, int b) {
    IntSupplier body =
        () ->
            switch (Integer.toString(a)) {
              case "1" -> b;
              case "2" -> b + 1;
              default -> a;
            };
    return body.getAsInt();
  }

  // Re-assignments nothing reads: skipped as equivalent, and checked to be.

  static int overwritten(int a, int b) {
    int x = a;
    x = b;
    x = a + b + 1;
    return x;
  }

  static int neverRead(int a, int b) {
    int x = a;
    x = b;
    return a;
  }

  static int overwrittenInBothArms(int a, int b) {
    int x = a;
    x = b;
    if (a > b) {
      x = 1;
    } else {
      x = 2;
    }
    return x;
  }
}
