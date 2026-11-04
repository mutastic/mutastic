package shapes;

/// The ways javac compiles a condition, one to a method: each card a mutant here gets is checked
/// against the source with its claim written in. Every method takes two ints and returns an int,
/// and no condition's text repeats within its method, so a card's words point at one place. Each
/// loop has a second bound, so a claim that forces one condition still ends.
public final class Shapes {
  private Shapes() {}

  /// A predicate for the conditions on a boolean, not mutated by the check.
  static boolean even(int x) {
    return x % 2 == 0;
  }

  static boolean small(int x) {
    return x < 2;
  }

  static int plainIf(int a, int b) {
    if (a > b) {
      return 1;
    }
    return 2;
  }

  static int notIf(int a, int b) {
    if (!(a >= b)) {
      return 1;
    }
    return 2;
  }

  static int ifElse(int a, int b) {
    int r;
    if (a <= 1) {
      r = 1;
    } else {
      r = 2;
    }
    return r + b;
  }

  static int and(int a, int b) {
    if (a > 0 && b < 2) {
      return 1;
    }
    return 2;
  }

  static int or(int a, int b) {
    if (a > 0 || b < 2) {
      return 1;
    }
    return 2;
  }

  static int orOnABoolean(int a, int b) {
    if (even(a) || b > -1) {
      return 1;
    }
    return 2;
  }

  static int andOnBooleans(int a, int b) {
    if (even(a) && small(b)) {
      return 1;
    }
    return 2;
  }

  static int notABoolean(int a, int b) {
    if (!even(a)) {
      return 1;
    }
    return 2 + b;
  }

  static int notOr(int a, int b) {
    if (!(even(a) || small(b))) {
      return 1;
    }
    return 2;
  }

  static int mixed(int a, int b) {
    if (a < -1 || b > 0 && even(a)) {
      return 1;
    }
    return 2;
  }

  static int threeAnds(int a, int b) {
    if (a != 0 && b != 0 && a != b) {
      return 1;
    }
    return 2;
  }

  static int ternary(int a, int b) {
    return a == b ? 1 : 2;
  }

  static int nestedTernary(int a, int b) {
    return a > 1 ? (b > 1 ? 1 : 2) : 3;
  }

  static int booleanValue(int a, int b) {
    boolean over = a >= b;
    return over ? 1 : 2;
  }

  static int returnsAComparison(int a, int b) {
    return isAbove(a, b) ? 1 : 2;
  }

  static boolean isAbove(int a, int b) {
    return a > b + 1;
  }

  static int whileLoop(int a, int b) {
    int n = 0;
    while (n < a && n < 4) {
      n++;
    }
    return n + b;
  }

  static int forLoop(int a, int b) {
    int sum = 0;
    for (int i = 0; i < a && i < 5; i++) {
      sum += b;
    }
    return sum;
  }

  static int doWhile(int a, int b) {
    int n = 0;
    do {
      n++;
    } while (n <= a && n < 5);
    return n + b;
  }

  static int earlyReturns(int a, int b) {
    if (a < 0) {
      return -1;
    }
    if (b == 0) {
      return 0;
    }
    return 1;
  }

  static int elseIfChain(int a, int b) {
    if (a > 2) {
      return 3;
    } else if (a > b) {
      return 2;
    } else if (b != 1) {
      return 1;
    }
    return 0;
  }

  static int againstZero(int a, int b) {
    if (a != 0) {
      return 1;
    }
    return 2 + b;
  }

  static int onALong(int a, int b) {
    long wide = a;
    if (wide > b) {
      return 1;
    }
    return 2;
  }

  static int nullOr(int a, int b) {
    Integer boxed = a > 0 ? a : null;
    if (boxed == null || boxed > b) {
      return 1;
    }
    return 2;
  }

  static int notNullAnd(int a, int b) {
    String name = b < 0 ? null : "n" + b;
    if (name != null && a != 3) {
      return name.length();
    }
    return 0;
  }

  static int switchGuard(int a, int b) {
    Object boxed = a;
    return switch (boxed) {
      case Integer i when i > b -> 1;
      default -> 2;
    };
  }
}
