package io.mutastic.engine.fixture;

/// The `||` shapes the implied-test rule judges: the ones it skips, and the near misses it must
/// leave alone.
public final class Implied {
  private final int floor = 1;

  private Implied() {}

  static boolean inCredit(int balance) {
    return balance >= 1;
  }

  static boolean between(int x) {
    return x > 0 && x < 9;
  }

  static boolean overTwo(int x, int y) {
    return x > 2;
  }

  boolean aboveFloor(int x) {
    return x >= floor;
  }

  static boolean notEqualToOne(int x) {
    return x != 1;
  }

  /// Skipped: `inCredit` is `balance >= 1`, within `balance > -1000`.
  static int byACall(int balance) {
    if (inCredit(balance) || balance > -1000) {
      return 0;
    }
    return 500;
  }

  /// Skipped, and its boundary too: `x > 5`, or `x >= 5`, is within `x > 3`.
  static int byAComparison(int x) {
    if (x > 5 || x > 3) {
      return 1;
    }
    return 2;
  }

  /// Forced false is skipped; the boundary `x >= 3` isn't within `x >= 4`.
  static int atTheSameEdge(int x) {
    if (x > 3 || x >= 4) {
      return 1;
    }
    return 2;
  }

  /// Not within: `x > 0` lets 1 in, which `x > 5` doesn't.
  static int notImplied(int x) {
    if (x > 0 || x > 5) {
      return 1;
    }
    return 2;
  }

  /// Different locals.
  static int otherLocals(int x, int y) {
    if (x > 5 || y > 3) {
      return 1;
    }
    return 2;
  }

  /// `!=` isn't one range.
  static int notEqual(int x) {
    if (x != 1 || x > 3) {
      return 1;
    }
    return 2;
  }

  /// An `&&`: the first test jumps past the body, not into it.
  static int and(int x) {
    if (x > 5 && x > 3) {
      return 1;
    }
    return 2;
  }

  /// Calls whose body isn't one comparison of the parameter, or that aren't this class's static
  /// methods: not read.
  static int callsItCantRead(int x) {
    if (between(x) || x > 0) {
      return 1;
    }
    if (overTwo(x, x) || x > 0) {
      return 2;
    }
    if (new Implied().aboveFloor(x) || x > 0) {
      return 3;
    }
    if (notEqualToOne(x) || x > -5) {
      return 4;
    }
    if (Character.isDigit(x) || x > 0) {
      return 5;
    }
    return 6;
  }
}
