package arithmetic;

/// Each method's change, written out: `xClaim` is `x` as its card says the mutant makes it. The
/// fixture's inputs are negative as well as positive, so each rounds differently for some of them.
class Divisions {
  static int aFloorDivision(int a, int b) {
    return Math.floorDiv(a, b);
  }

  static int aFloorDivisionClaim(int a, int b) {
    return a / b;
  }

  static int aFloorModulus(int a, int b) {
    return Math.floorMod(a, b);
  }

  static int aFloorModulusClaim(int a, int b) {
    return a % b;
  }

  static long aLongFloorDivision(int a, int b) {
    return Math.floorDiv((long) a * 3, (long) b);
  }

  static long aLongFloorDivisionClaim(int a, int b) {
    return (long) a * 3 / (long) b;
  }

  static long aLongFloorModulus(int a, int b) {
    return StrictMath.floorMod((long) a * 3, (long) b);
  }

  static long aLongFloorModulusClaim(int a, int b) {
    return (long) a * 3 % (long) b;
  }

  static long aLongByAnInt(int a, int b) {
    return Math.floorDiv((long) a * 3, b);
  }

  static long aLongByAnIntClaim(int a, int b) {
    return (long) a * 3 / b;
  }

  static int aLongModulusByAnInt(int a, int b) {
    return Math.floorMod((long) a * 3, b);
  }

  static int aLongModulusByAnIntClaim(int a, int b) {
    return (int) ((long) a * 3 % b);
  }

  static int inASum(int a, int b) {
    return 1 + Math.floorMod(a + 1, b);
  }

  static int inASumClaim(int a, int b) {
    return 1 + (a + 1) % b;
  }

  // Can't change the result: neither number is ever negative.

  static int aLengthByAConstant(int a, int b) {
    return Math.floorMod(String.valueOf(a).length(), 3) + b;
  }

  static int anArraysLengthByAConstant(int a, int b) {
    return Math.floorDiv(new int[Math.abs(a % 5)].length, 2) + b;
  }

  // Runs: a negative constant can make the two differ.

  static int byANegativeConstant(int a, int b) {
    return Math.floorMod(String.valueOf(a).length(), -3) + b;
  }

  static int byANegativeConstantClaim(int a, int b) {
    return String.valueOf(a).length() % -3 + b;
  }
}
