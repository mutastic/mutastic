package arithmetic;

/// Each method's change, written out: `xClaim_first` is `x` as its card says it is when it keeps
/// its first value, and `xClaim_second` when it keeps its second.
class KeptOperands {
  static int anIntSum(int a, int b) {
    int x = a;
    int y = b;
    return x + y;
  }

  static int anIntSumClaim_first(int a, int b) {
    int x = a;
    int y = b;
    return x;
  }

  static int anIntSumClaim_second(int a, int b) {
    int x = a;
    int y = b;
    return y;
  }

  static int anIntQuotient(int a, int b) {
    int x = a;
    int y = b;
    return x / y;
  }

  static int anIntQuotientClaim_first(int a, int b) {
    int x = a;
    int y = b;
    return x;
  }

  static int anIntQuotientClaim_second(int a, int b) {
    int x = a;
    int y = b;
    return y;
  }

  static long aLongSum(int a, int b) {
    long x = (long) a;
    long y = (long) b;
    return x + y;
  }

  static long aLongSumClaim_first(int a, int b) {
    long x = (long) a;
    long y = (long) b;
    return x;
  }

  static long aLongSumClaim_second(int a, int b) {
    long x = (long) a;
    long y = (long) b;
    return y;
  }

  static long aLongQuotient(int a, int b) {
    long x = (long) a;
    long y = (long) b;
    return x / y;
  }

  static long aLongQuotientClaim_first(int a, int b) {
    long x = (long) a;
    long y = (long) b;
    return x;
  }

  static long aLongQuotientClaim_second(int a, int b) {
    long x = (long) a;
    long y = (long) b;
    return y;
  }

  static float aFloatSum(int a, int b) {
    float x = a;
    float y = b;
    return x + y;
  }

  static float aFloatSumClaim_first(int a, int b) {
    float x = a;
    float y = b;
    return x;
  }

  static float aFloatSumClaim_second(int a, int b) {
    float x = a;
    float y = b;
    return y;
  }

  static float aFloatQuotient(int a, int b) {
    float x = a;
    float y = b;
    return x / y;
  }

  static float aFloatQuotientClaim_first(int a, int b) {
    float x = a;
    float y = b;
    return x;
  }

  static float aFloatQuotientClaim_second(int a, int b) {
    float x = a;
    float y = b;
    return y;
  }

  static double aDoubleSum(int a, int b) {
    double x = a;
    double y = b;
    return x + y;
  }

  static double aDoubleSumClaim_first(int a, int b) {
    double x = a;
    double y = b;
    return x;
  }

  static double aDoubleSumClaim_second(int a, int b) {
    double x = a;
    double y = b;
    return y;
  }

  static double aDoubleQuotient(int a, int b) {
    double x = a;
    double y = b;
    return x / y;
  }

  static double aDoubleQuotientClaim_first(int a, int b) {
    double x = a;
    double y = b;
    return x;
  }

  static double aDoubleQuotientClaim_second(int a, int b) {
    double x = a;
    double y = b;
    return y;
  }

  private static int[] counted = new int[1];

  private static int counting(int value) {
    counted[0]++;
    return value;
  }

  /// A call in the value that isn't kept still runs.
  static String aCall(int a, int b) {
    counted[0] = 0;
    int sum = counting(a) + b;
    return sum + "," + counted[0];
  }

  static String aCallClaim_first(int a, int b) {
    counted[0] = 0;
    int sum = counting(a);
    return sum + "," + counted[0];
  }

  static String aCallClaim_second(int a, int b) {
    counted[0] = 0;
    counting(a);
    int sum = b;
    return sum + "," + counted[0];
  }

  /// Only the variable is kept, and the one that isn't a constant.
  static int plusOne(int a, int b) {
    return a + 1;
  }

  static int plusOneClaim(int a, int b) {
    return a;
  }

  static int twice(int a, int b) {
    return 2 * a;
  }

  static int twiceClaim(int a, int b) {
    return a;
  }

  // Skipped as arid: only how big the list starts.

  static int aCapacity(int a, int b) {
    return new java.util.ArrayList<String>(a + 1).size();
  }

  // Nothing to keep one of.

  static int aShift(int a, int b) {
    return a << b;
  }

  static int aNegation(int a, int b) {
    return -a;
  }
}
