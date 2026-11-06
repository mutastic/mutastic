package exceptions;

import java.io.StringReader;

/// Each change, written out: `xClaim` is `x` as its card says the mutant makes it, and
/// `xClaim_NumberFormatException` for the type that mutant no longer catches.
class Throws {
  static int aGuard(String a, String b) {
    if (a.equals(b)) {
      throw new IllegalStateException("same");
    }
    return a.length();
  }

  static int aGuardClaim(String a, String b) {
    return a.length();
  }

  static String anElse(String a, String b) {
    String result;
    if (a.equals(b)) {
      result = a;
    } else {
      throw new IllegalArgumentException();
    }
    return result;
  }

  static int inALoop(String a, String b) {
    int total = 0;
    for (int at = 0; at < a.length(); at++) {
      if (a.charAt(at) == 'b') {
        throw new IllegalStateException();
      }
      total++;
    }
    return total;
  }

  static int inALoopClaim(String a, String b) {
    return a.length();
  }

  /// Carrying on from a `catch` is what a swallowed exception does.
  static int wrapped(String a, String b) {
    int value = -1;
    try {
      value = Integer.parseInt(a);
    } catch (NumberFormatException e) {
      throw new IllegalArgumentException(e);
    }
    return value;
  }

  static int wrappedClaim(String a, String b) {
    int value = -1;
    try {
      value = Integer.parseInt(a);
    } catch (NumberFormatException e) {
    }
    return value;
  }

  /// With the throw gone, the code runs on into the next arm.
  static int inASwitch(String a, String b) {
    switch (a.length()) {
      case 1:
        throw new IllegalStateException();
      case 2:
        return 2;
      default:
        return 0;
    }
  }

  static int inASwitchClaim(String a, String b) {
    switch (a.length()) {
      case 1:
      case 2:
        return 2;
      default:
        return 0;
    }
  }

  // What has no change to run.

  /// Nothing comes after it, so there's nothing to carry on with.
  static int aRethrow(String a, String b) {
    try {
      return Integer.parseInt(a);
    } catch (NumberFormatException e) {
      throw e;
    }
  }

  static int atTheEnd(String a, String b) {
    throw new IllegalStateException();
  }

  static void anAssert(String a, String b) {
    assert a.equals(b) : "not the same";
  }

  static int aFinally(String a, String b) {
    try {
      return a.length();
    } finally {
      b.length();
    }
  }

  static int synchronizedOn(String a, String b) {
    synchronized (a) {
      return b.length();
    }
  }

  static int aResource(String a, String b) throws java.io.IOException {
    try (var reader = new StringReader(a)) {
      return reader.read();
    }
  }

  sealed interface Shape permits Round, Flat {}

  record Round() implements Shape {}

  record Flat() implements Shape {}

  static int exhaustive(String a, String b) {
    Shape shape = a.equals(b) ? new Round() : new Flat();
    return switch (shape) {
      case Round round -> 1;
      case Flat flat -> 2;
    };
  }

  // Multi-catch.

  static String twoTypes(String a, String b) {
    try {
      return String.valueOf(Integer.parseInt(a) + b.charAt(2));
    } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
      return "caught";
    }
  }

  static String twoTypesClaim_NumberFormatException(String a, String b) {
    try {
      return String.valueOf(Integer.parseInt(a) + b.charAt(2));
    } catch (StringIndexOutOfBoundsException e) {
      return "caught";
    }
  }

  static String twoTypesClaim_StringIndexOutOfBoundsException(String a, String b) {
    try {
      return String.valueOf(Integer.parseInt(a) + b.charAt(2));
    } catch (NumberFormatException e) {
      return "caught";
    }
  }

  /// One `catch` copied with its `finally`: each copy loses the type.
  static String inAFinally(String a, String b) {
    var seen = new StringBuilder();
    try {
      seen.append(a);
    } finally {
      try {
        seen.append(Integer.parseInt(a) + b.charAt(2));
      } catch (NumberFormatException | StringIndexOutOfBoundsException e) {
        seen.append("caught");
      }
    }
    return seen.toString();
  }

  static String inAFinallyClaim_NumberFormatException(String a, String b) {
    var seen = new StringBuilder();
    try {
      seen.append(a);
    } finally {
      try {
        seen.append(Integer.parseInt(a) + b.charAt(2));
      } catch (StringIndexOutOfBoundsException e) {
        seen.append("caught");
      }
    }
    return seen.toString();
  }

  static String inAFinallyClaim_StringIndexOutOfBoundsException(String a, String b) {
    var seen = new StringBuilder();
    try {
      seen.append(a);
    } finally {
      try {
        seen.append(Integer.parseInt(a) + b.charAt(2));
      } catch (NumberFormatException e) {
        seen.append("caught");
      }
    }
    return seen.toString();
  }

  /// Two `catch`es of one type each aren't a multi-catch.
  static String twoCatches(String a, String b) {
    try {
      return String.valueOf(Integer.parseInt(a) + b.charAt(2));
    } catch (NumberFormatException e) {
      return "number";
    } catch (StringIndexOutOfBoundsException e) {
      return "index";
    }
  }
}
