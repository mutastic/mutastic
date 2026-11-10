package shapes;

/// The ways javac compiles a condition, one to a method: each card a mutant here gets is checked
/// against the source with its claim written in. Every method takes two ints and returns an int,
/// and no condition's text repeats within its method, so a card's words point at one place. Each
/// loop has a second bound, so a claim that forces one condition still ends.
public final class Shapes {
  private Shapes() {}

  /// A constant javac inlines: the bytecode has `3`, the source `EDGE`.
  static final int EDGE = 3;

  /// A string constant javac inlines too: the bytecode has `"3"`, the source `TEXT`.
  static final String TEXT = "3";

  /// Read as a static field, not inlined as a constant would be.
  static int limit = 2;

  static final Object FIRST = new Object();
  static final Object SECOND = new Object();

  /// A predicate for the conditions on a boolean, not mutated by the check.
  static boolean even(int x) {
    return x % 2 == 0;
  }

  static boolean small(int x) {
    return x < 2;
  }

  static boolean atLeastOne(int x) {
    return x >= 1;
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

  static int staticField(int a, int b) {
    if (a > limit || b == Other.LIMIT) {
      return 1;
    }
    return 2;
  }

  static int sameObject(int a, int b) {
    Object picked = a > 0 ? FIRST : SECOND;
    if (picked == FIRST && b != 1) {
      return 1;
    }
    return 2;
  }

  static int splitOverLines(int a, int b) {
    if (a > 0
        && b > 1) {
      return 1;
    }
    return 2;
  }

  static int splitReturn(int a, int b) {
    return a >= -1
            && b <= 2
        ? 1
        : 2;
  }

  static int impliedByACall(int a, int b) {
    if (atLeastOne(a) || a > -2) {
      return 1;
    }
    return 2 + b;
  }

  static int impliedComparison(int a, int b) {
    if (a > 2 || a > 0) {
      return 1;
    }
    return 2 + b;
  }

  static int notImplied(int a, int b) {
    if (a > 0 || a > 2) {
      return 1;
    }
    return 2 + b;
  }

  static int namedConstant(int a, int b) {
    if (b < 0 || a > EDGE) {
      return 1;
    }
    return 2;
  }

  /// A string the bytecode has as `"3"`, an argument the source names.
  static int namedText(int a, int b) {
    if (String.valueOf(a).equals(TEXT)) {
      return 1;
    }
    return 2;
  }

  /// The same string, a receiver the source names.
  static int namedTextFirst(int a, int b) {
    if (TEXT.equals(String.valueOf(b))) {
      return 1;
    }
    return 2;
  }

  /// A `final` local set to a string: javac inlines it as it does a constant.
  static int finalLocalText(int a, int b) {
    final String three = "3";
    if (!String.valueOf(a).equals(three)) {
      return 1;
    }
    return 2;
  }

  /// A class literal: the bytecode loads the class itself, the source writes `Number.class`.
  static int classLiteral(int a, int b) {
    Class<?> type = a > b ? Integer.class : String.class;
    if (Number.class.isAssignableFrom(type)) {
      return 1;
    }
    return 2;
  }

  /// A primitive's class: the bytecode reads `Integer.TYPE`, the source writes `int.class`.
  static int primitiveClass(int a, int b) {
    Class<?> type = a > b ? int.class : long.class;
    if (type == int.class) {
      return 1;
    }
    return 2;
  }

  static int clampLow(int a, int b) {
    return a < 3 ? 3 : a;
  }

  static int clampZero(int a, int b) {
    return a < 0 ? 0 : a;
  }

  static int clampLong(int a, int b) {
    long wide = a;
    return (int) (wide < 1L ? 1L : wide);
  }

  static int raiseTo(int a, int b) {
    int x = a;
    if (x < 2) {
      x = 2;
    }
    return x + b;
  }

  static int clampBoxed(int a, int b) {
    Integer chosen = a;
    return chosen > 3 ? chosen : 3;
  }

  static int charEdge(int a, int b) {
    char c = (char) ('a' + a);
    return c >= 'b' ? 1 : 0;
  }

  static int slashBefore(int a, int b) {
    return "ab/c".indexOf('/') > a ? 1 : 0;
  }

  static int notAClamp(int a, int b) {
    return a < 3 ? 4 : a;
  }

  static int clampToSomethingElse(int a, int b) {
    return a > 2 ? 2 : a + b;
  }

  static int switchGuard(int a, int b) {
    Object boxed = a;
    return switch (boxed) {
      case Integer i when i > b -> 1;
      default -> 2;
    };
  }

  static int equalsAnExpression(int a, int b) {
    if (a == b + 1) {
      return 1;
    }
    return 2;
  }

  static int notEqualAsWritten(int a, int b) {
    if (a != b || b > 3) {
      return 1;
    }
    return 2;
  }

  static int lengthNotZero(int a, int b) {
    String text = "x".repeat(Math.max(0, a));
    if (text.length() != 0) {
      return 1;
    }
    return 2 + b;
  }

  static int equalLongs(int a, int b) {
    long wide = a;
    if (wide == b) {
      return 1;
    }
    return 2;
  }

  static int booleansEqual(int a, int b) {
    if (even(a) == small(b)) {
      return 1;
    }
    return 2;
  }

  /// `NaN` where `a` is 0: a comparison's mutant must treat it as Java does.
  static int belowAFloat(int a, int b) {
    float x = a == 0 ? Float.NaN : a;
    if (x < b) {
      return 1;
    }
    return 2;
  }

  static int atLeastAFloatAsWritten(int a, int b) {
    float x = a == 1 ? Float.NaN : a;
    if (x >= b || b == 4) {
      return 1;
    }
    return 2;
  }

  static int aboveADouble(int a, int b) {
    double x = a == 2 ? Double.NaN : a;
    return x > b ? 1 : 2;
  }

  static int equalFloats(int a, int b) {
    float x = a == -1 ? Float.NaN : a;
    if (x == b) {
      return 1;
    }
    return 2;
  }

  static int notEqualDoubles(int a, int b) {
    double x = a == -2 ? Double.NaN : a;
    if (x != b) {
      return 1;
    }
    return 2;
  }

  static int sizeAboveZero(int a, int b) {
    var copies = java.util.Collections.nCopies(Math.max(0, a), b);
    if (copies.size() > 0) {
      return 1;
    }
    return 2;
  }

  /// The same bytecode as `a > 0 && b > 0`, but with no connector in the source: a line of its
  /// own keeps it from being one.
  static int nestedIfs(int a, int b) {
    if (a > 0) {
      if (b > 0) {
        return 1;
      }
    }
    return 2;
  }
}
