package bodies;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/// Each method's change, written out: `xClaim` is `x` as its card says the mutant makes it, and
/// `xClaim_true` for the mutant whose label ends in `true`.
class Bodies {
  private static final List<String> NAMES = List.of("x");

  private String name = "n";

  Bodies() {
    name = name + "!";
  }

  static boolean aTest(String a, String b) {
    return a.contains(b);
  }

  static boolean aTestClaim_true(String a, String b) {
    return true;
  }

  static boolean aTestClaim_false(String a, String b) {
    return false;
  }

  static boolean checksThenFalse(String a, String b) {
    if (a.equals(b)) {
      throw new IllegalStateException();
    }
    return false;
  }

  static boolean checksThenFalseClaim(String a, String b) {
    return true;
  }

  static boolean onlyFalse(String a, String b) {
    return false;
  }

  static boolean onlyFalseClaim(String a, String b) {
    return true;
  }

  static int anInt(String a, String b) {
    return a.length() + b.length();
  }

  static int anIntClaim(String a, String b) {
    return 0;
  }

  static int onlyZero(String a, String b) {
    return 0;
  }

  static int onlyZeroClaim(String a, String b) {
    return 1;
  }

  static long aLong(String a, String b) {
    return (long) a.length() + b.length();
  }

  static long aLongClaim(String a, String b) {
    return 0L;
  }

  static double aDouble(String a, String b) {
    return a.length() / 2.0;
  }

  static double aDoubleClaim(String a, String b) {
    return 0.0;
  }

  static float aFloat(String a, String b) {
    return a.length() / 2f;
  }

  static float aFloatClaim(String a, String b) {
    return 0f;
  }

  static char aChar(String a, String b) {
    return a.charAt(0);
  }

  static char aCharClaim(String a, String b) {
    return 0;
  }

  static byte aByte(String a, String b) {
    return (byte) a.length();
  }

  static byte aByteClaim(String a, String b) {
    return 0;
  }

  static Integer aBox(String a, String b) {
    return a.length();
  }

  static Integer aBoxClaim(String a, String b) {
    return 0;
  }

  static String text(String a, String b) {
    return a + b;
  }

  static String textClaim(String a, String b) {
    return "";
  }

  static List<String> aList(String a, String b) {
    return List.of(a, b);
  }

  static List<String> aListClaim(String a, String b) {
    return List.of();
  }

  static Set<String> aSet(String a, String b) {
    return Set.of(a + b);
  }

  static Set<String> aSetClaim(String a, String b) {
    return Set.of();
  }

  static Map<String, String> aMap(String a, String b) {
    return Map.of(a, b);
  }

  static Map<String, String> aMapClaim(String a, String b) {
    return Map.of();
  }

  static Optional<String> anOptional(String a, String b) {
    return Optional.of(a);
  }

  static Optional<String> anOptionalClaim(String a, String b) {
    return Optional.empty();
  }

  static Object anObject(String a, String b) {
    return a;
  }

  static Object anObjectClaim(String a, String b) {
    return null;
  }

  static String[] anArray(String a, String b) {
    return new String[] {a, b};
  }

  static String[] anArrayClaim(String a, String b) {
    return null;
  }

  /// Several returns have no one value they give today.
  static int twoReturns(String a, String b) {
    if (a.equals(b)) {
      return 1;
    }
    return 2;
  }

  static int twoReturnsClaim(String a, String b) {
    return 0;
  }

  /// Its handler goes with the rest of the code.
  static int aTry(String a, String b) {
    try {
      return Integer.parseInt(a);
    } catch (NumberFormatException e) {
      return -1;
    }
  }

  static int aTryClaim(String a, String b) {
    return 0;
  }

  static int aLoop(String a, String b) {
    int total = 0;
    for (int at = 0; at < a.length(); at++) {
      total += at + 1;
    }
    return total;
  }

  static int aLoopClaim(String a, String b) {
    return 0;
  }

  int onAnInstance(String a, String b) {
    return name.length() + a.length();
  }

  int onAnInstanceClaim(String a, String b) {
    return 0;
  }

  static void returnsNothing(String a, String b) {
    if (a.equals(b)) {
      throw new IllegalStateException();
    }
  }

  static void returnsNothingClaim(String a, String b) {}

  /// Its code does something first, so taking it away isn't the same though the value is.
  static List<String> worksThenEmpty(String a, String b) {
    if (a.equals(b)) {
      throw new IllegalStateException();
    }
    return List.of();
  }

  static List<String> worksThenEmptyClaim(String a, String b) {
    return List.of();
  }

  static String aLambda(String a, String b) {
    Supplier<String> joined = () -> a + "!";
    return joined.get();
  }

  static String aLambdaClaim(String a, String b) {
    return "";
  }

  // What's left alone, or skipped.

  static void alreadyEmpty(String a, String b) {}

  static Object onlyNull(String a, String b) {
    return null;
  }

  static List<String> onlyAnEmptyList(String a, String b) {
    return List.of();
  }

  String getName() {
    return name;
  }

  @Override
  public String toString() {
    return "Bodies " + name;
  }

  @Override
  public int hashCode() {
    return name.hashCode();
  }
}
