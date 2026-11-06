package arithmetic;

import java.util.ArrayList;

/// Each number's change, written out: `xClaim` is `x` with the number as a parameter, so one claim
/// says what every value's mutant does.
class ConstantValues {
  private static final int LIMIT = 100;
  private static final long BIG_LIMIT = 100L;

  static String threshold(int a, int b) {
    return a >= 100 ? "over" : "under";
  }

  static String thresholdClaim(int a, int b, int value) {
    return a >= value ? "over" : "under";
  }

  /// A `static final`: javac writes `100` here, and the field's own value is never read.
  static String inlined(int a, int b) {
    return a >= LIMIT ? "over" : "under";
  }

  static String inlinedClaim(int a, int b, int value) {
    return a >= value ? "over" : "under";
  }

  static long inlinedLong(int a, int b) {
    return a + BIG_LIMIT;
  }

  static long inlinedLongClaim(int a, int b, long value) {
    return a + value;
  }

  static int small(int a, int b) {
    return a + 3;
  }

  static int smallClaim(int a, int b, int value) {
    return a + value;
  }

  static int negative(int a, int b) {
    return a + -7;
  }

  static int negativeClaim(int a, int b, int value) {
    return a + value;
  }

  static int aShort(int a, int b) {
    return a * 1000;
  }

  static int aShortClaim(int a, int b, int value) {
    return a * value;
  }

  static int fromThePool(int a, int b) {
    return a + 100000;
  }

  static int fromThePoolClaim(int a, int b, int value) {
    return a + value;
  }

  static int theBiggest(int a, int b) {
    return a + Integer.MAX_VALUE;
  }

  static int theBiggestClaim(int a, int b, int value) {
    return a + value;
  }

  static boolean oneOnItsOwn(int a, int b) {
    return a == 1;
  }

  static boolean oneOnItsOwnClaim(int a, int b, int value) {
    return a == value;
  }

  static boolean belowOne(int a, int b) {
    return a < 1;
  }

  static boolean belowOneClaim(int a, int b, int value) {
    return a < value;
  }

  static int plusOne(int a, int b) {
    return a + 1;
  }

  static int plusOneClaim(int a, int b, int value) {
    return a + value;
  }

  static int returned(int a, int b) {
    return 7;
  }

  static int returnedClaim(int a, int b, int value) {
    return value;
  }

  static int aFirstValue(int a, int b) {
    int total = 5;
    total += a;
    return total;
  }

  static int aFirstValueClaim(int a, int b, int value) {
    int total = value;
    total += a;
    return total;
  }

  static int aLaterValue(int a, int b) {
    int total = a;
    total = 0;
    return total + b;
  }

  static int aLaterValueClaim(int a, int b, int value) {
    int total = a;
    total = value;
    return total + b;
  }

  static int anArgument(int a, int b) {
    return Math.max(a, 3);
  }

  static int anArgumentClaim(int a, int b, int value) {
    return Math.max(a, value);
  }

  static int anArrayValue(int a, int b) {
    int[] values = {a, 5};
    return values[0] + values[1];
  }

  static int anArrayValueClaim(int a, int b, int value) {
    int[] values = {a, value};
    return values[0] + values[1];
  }

  static long aLong(int a, int b) {
    return a + 3L;
  }

  static long aLongClaim(int a, int b, long value) {
    return a + value;
  }

  static long aLongZero(int a, int b) {
    return a + 0L;
  }

  static long aLongZeroClaim(int a, int b, long value) {
    return a + value;
  }

  static float aFloat(int a, int b) {
    return a * 0.5f;
  }

  static float aFloatClaim(int a, int b, float value) {
    return a * value;
  }

  static double aDouble(int a, int b) {
    return a * 0.25;
  }

  static double aDoubleClaim(int a, int b, double value) {
    return a * value;
  }

  static double aDoubleOne(int a, int b) {
    return a + 1.0;
  }

  static double aDoubleOneClaim(int a, int b, double value) {
    return a + value;
  }

  static boolean aLetter(int a, int b) {
    return (char) a == 'b';
  }

  static boolean aLetterClaim(int a, int b, int value) {
    return (char) a == value;
  }

  static String aLetterGiven(int a, int b) {
    return String.valueOf('b') + a;
  }

  static String aLetterGivenClaim(int a, int b, char value) {
    return String.valueOf(value) + a;
  }

  static String aFirstArgument(int a, int b) {
    return twoArguments('b', a);
  }

  static String aFirstArgumentClaim(int a, int b, char value) {
    return twoArguments(value, a);
  }

  private static String twoArguments(char letter, int number) {
    return letter + ":" + number;
  }

  // Skipped as arid: what a test sees doesn't change, or it's a size.

  static int anArraySize(int a, int b) {
    return new int[16].length + a;
  }

  static int aCapacity(int a, int b) {
    return new ArrayList<String>(16).size() + a;
  }

  static int aWait(int a, int b) throws InterruptedException {
    Thread.sleep(25L);
    return a;
  }

  static int aCounterFromZero(int a, int b) {
    int total = 0;
    return total + a;
  }

  static long aTotalFromOne(int a, int b) {
    long total = 1L;
    return total * a;
  }

  static double aSumFromZero(int a, int b) {
    double sum = 0.0;
    return sum + a;
  }

  static int aLoopFromZero(int a, int b) {
    int[] values = {a, b};
    int total = a;
    for (int i = 0; i < values.length; i++) {
      total += values[i];
    }
    return total;
  }

  static int aSplitsLimit(int a, int b) {
    return String.valueOf(a).split("0", -1).length;
  }

  // Not numbers a source wrote as numbers: each has no mutant.

  static boolean aTrue(int a, int b) {
    return true;
  }

  static boolean aFalseVariable(int a, int b) {
    boolean found = false;
    return found;
  }

  static boolean aComparisonsResult(int a, int b) {
    return a < b;
  }

  static String aBooleanArgument(int a, int b) {
    return String.valueOf(false);
  }

  static boolean comparedWithTrue(int a, int b) {
    boolean flag = a > b;
    return flag == true;
  }

  static boolean[] aBooleanArray(int a, int b) {
    return new boolean[] {true};
  }

  static int oneOrZero(int a, int b) {
    return a > b ? 1 : 0;
  }

  static int theComplement(int a, int b) {
    return ~a;
  }

  /// Its size and indexes are javac's, and the values aren't constants.
  static String[] anArrayOfVariables(int a, int b) {
    return new String[] {"x" + a, "y" + b};
  }

  static String varargs(int a, int b) {
    return String.format("%s %s", a, b);
  }

  static int enhancedFor(int a, int b) {
    int total = a;
    for (int each : new int[] {a, b}) {
      total += each;
    }
    return total;
  }

  static int stringSwitch(int a, int b) {
    switch (String.valueOf(a)) {
      case "first":
        return a;
      case "second":
        return b;
      default:
        return a + b;
    }
  }
}
