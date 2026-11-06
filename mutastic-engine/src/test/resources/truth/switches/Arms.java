package switches;

/// Each arm's change, written out: `xClaim_2` is `x` with its `case 2` removed, as its card says. The
/// inputs are `(int, int)`, and the shapes that switch on something else make it from `a`.
class Arms {
  enum Color {
    RED,
    GREEN,
    BLUE
  }

  sealed interface Shape permits Circle, Square {}

  record Circle(int r) implements Shape {}

  record Square(int s) implements Shape {}

  sealed interface Marked permits Level, Other {}

  enum Level implements Marked {
    LOW,
    HIGH
  }

  record Other(int value) implements Marked {}

  private static Marked marked(int a) {
    return a == 1 ? Level.LOW : a == 2 ? Level.HIGH : new Other(a);
  }

  private static Color color(int a) {
    return Color.values()[Math.floorMod(a, 3)];
  }

  private static Shape shape(int a) {
    return a > 0 ? new Circle(a) : new Square(a);
  }

  static int oldStyle(int a, int b) {
    int r = b;
    switch (a) {
      case 1:
        r = 10;
        break;
      case 2:
        r = 20;
      case 100:
        r += 1;
        break;
      default:
        r = -1;
    }
    return r;
  }

  static int oldStyleClaim_1(int a, int b) {
    int r = b;
    switch (a) {
      case 2:
        r = 20;
      case 100:
        r += 1;
        break;
      default:
        r = -1;
    }
    return r;
  }

  static int oldStyleClaim_2(int a, int b) {
    int r = b;
    switch (a) {
      case 1:
        r = 10;
        break;
      case 100:
        r += 1;
        break;
      default:
        r = -1;
    }
    return r;
  }

  static int oldStyleClaim_100(int a, int b) {
    int r = b;
    switch (a) {
      case 1:
        r = 10;
        break;
      case 2:
        r = 20;
        r += 1;
        break;
      default:
        r = -1;
    }
    return r;
  }

  /// Two labels on one arm: each is its own mutant, and the other label keeps the arm.
  static int twoLabels(int a, int b) {
    switch (a) {
      case 1:
      case 2:
        return b;
      default:
        return 0;
    }
  }

  static int twoLabelsClaim_1(int a, int b) {
    return a == 2 ? b : 0;
  }

  static int twoLabelsClaim_2(int a, int b) {
    return a == 1 ? b : 0;
  }

  static int noDefault(int a, int b) {
    int r = b;
    switch (a) {
      case 3:
        r = 7;
    }
    return r;
  }

  static int noDefaultClaim(int a, int b) {
    return b;
  }

  /// A `tableswitch`, whose gaps go to the default and are no arms.
  static int aTableWithGaps(int a, int b) {
    switch (a) {
      case 1:
        return 1 + b;
      case 3:
        return 3 + b;
      default:
        return b;
    }
  }

  static int aTableWithGapsClaim_1(int a, int b) {
    return a == 3 ? 3 + b : b;
  }

  static int aTableWithGapsClaim_3(int a, int b) {
    return a == 1 ? 1 + b : b;
  }

  static int arrow(int a, int b) {
    return switch (a) {
      case 1 -> b;
      case 2 -> b + 1;
      default -> 0;
    };
  }

  static int arrowClaim_1(int a, int b) {
    return a == 2 ? b + 1 : 0;
  }

  static int arrowClaim_2(int a, int b) {
    return a == 1 ? b : 0;
  }

  static int yielding(int a, int b) {
    return switch (a) {
      case 1:
        yield b;
      default:
        yield 0;
    };
  }

  static int yieldingClaim(int a, int b) {
    return 0;
  }

  static int aLetter(int a, int b) {
    return switch ((char) a) {
      case 'a' -> b;
      default -> 0;
    };
  }

  static int aLetterClaim(int a, int b) {
    return 0;
  }

  static int aString(int a, int b) {
    switch (String.valueOf(a)) {
      case "1":
        return b;
      default:
        return 0;
    }
  }

  static int aStringClaim(int a, int b) {
    return 0;
  }

  static int aStringArrow(int a, int b) {
    return switch (String.valueOf(a)) {
      case "2" -> b;
      default -> 0;
    };
  }

  static int aStringArrowClaim(int a, int b) {
    return 0;
  }

  static int anEnum(int a, int b) {
    switch (color(a)) {
      case RED:
        return b;
      default:
        return 0;
    }
  }

  static int anEnumClaim(int a, int b) {
    return 0;
  }

  /// With no default, every value has an arm: javac's default throws, and so that's where the arm's
  /// values go.
  static int anExhaustiveEnum(int a, int b) {
    return switch (color(a)) {
      case RED -> b;
      case GREEN -> b + 1;
      case BLUE -> b + 2;
    };
  }

  static int anExhaustiveEnumClaim_0(int a, int b) {
    return switch (color(a)) {
      case RED -> throw new MatchException(null, null);
      case GREEN -> b + 1;
      case BLUE -> b + 2;
    };
  }

  static int anExhaustiveEnumClaim_1(int a, int b) {
    return switch (color(a)) {
      case RED -> b;
      case GREEN -> throw new MatchException(null, null);
      case BLUE -> b + 2;
    };
  }

  static int anExhaustiveEnumClaim_2(int a, int b) {
    return switch (color(a)) {
      case RED -> b;
      case GREEN -> b + 1;
      case BLUE -> throw new MatchException(null, null);
    };
  }

  static int aSealedType(int a, int b) {
    return switch (shape(a)) {
      case Circle c -> c.r() + b;
      case Square s -> s.s() - b;
    };
  }

  static int aSealedTypeClaim_Circle(int a, int b) {
    return switch (shape(a)) {
      case Circle c -> throw new MatchException(null, null);
      case Square s -> s.s() - b;
    };
  }

  static int aSealedTypeClaim_Square(int a, int b) {
    return switch (shape(a)) {
      case Circle c -> c.r() + b;
      case Square s -> throw new MatchException(null, null);
    };
  }

  static int aPatternWithADefault(int a, int b) {
    return switch (shape(a)) {
      case Circle c when c.r() > 2 -> b;
      default -> 0;
    };
  }

  static int aPatternWithADefaultClaim(int a, int b) {
    return 0;
  }

  /// A constant of an `enum` in a pattern switch, written with its type's name.
  static int aQualifiedConstant(int a, int b) {
    return switch (marked(a)) {
      case Level.LOW -> b;
      case Level.HIGH -> b + 1;
      case Other other -> other.value();
    };
  }

  static int aQualifiedConstantClaim_LOW(int a, int b) {
    return switch (marked(a)) {
      case Level.LOW -> throw new MatchException(null, null);
      case Level.HIGH -> b + 1;
      case Other other -> other.value();
    };
  }

  static int aQualifiedConstantClaim_HIGH(int a, int b) {
    return switch (marked(a)) {
      case Level.LOW -> b;
      case Level.HIGH -> throw new MatchException(null, null);
      case Other other -> other.value();
    };
  }

  static int aQualifiedConstantClaim_Other(int a, int b) {
    return switch (marked(a)) {
      case Level.LOW -> b;
      case Level.HIGH -> b + 1;
      case Other other -> throw new MatchException(null, null);
    };
  }

  /// An `enum` of another source file goes through javac's `$SwitchMap$` array, in a class of its
  /// own, whose numbers start at 1.
  private static java.time.DayOfWeek day(int a) {
    return java.time.DayOfWeek.of(Math.floorMod(a, 7) + 1);
  }

  static int aMappedEnum(int a, int b) {
    switch (day(a)) {
      case MONDAY:
        return b;
      default:
        return 0;
    }
  }

  static int aMappedEnumClaim(int a, int b) {
    return 0;
  }

  static int aMappedEnumWithAThrow(int a, int b) {
    return switch (java.time.Month.of(Math.floorMod(a, 2) + 1)) {
      case JANUARY -> b;
      case FEBRUARY -> b + 1;
      default -> throw new IllegalStateException();
    };
  }

  static int aMappedEnumWithAThrowClaim_1(int a, int b) {
    return switch (java.time.Month.of(Math.floorMod(a, 2) + 1)) {
      case FEBRUARY -> b + 1;
      default -> throw new IllegalStateException();
    };
  }

  static int aMappedEnumWithAThrowClaim_2(int a, int b) {
    return switch (java.time.Month.of(Math.floorMod(a, 2) + 1)) {
      case JANUARY -> b;
      default -> throw new IllegalStateException();
    };
  }

  /// An arm with nothing to do goes where the default goes: there's no change to see.
  static int anEmptyArm(int a, int b) {
    int r = b;
    switch (a) {
      case 1:
        break;
      case 2:
        r = 5;
    }
    return r;
  }

  static int anEmptyArmClaim(int a, int b) {
    return b;
  }
}
