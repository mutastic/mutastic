package arithmetic;

/// Each method's change, written out: `xClaim_LABEL` is `x` as its card says the mutant makes it.
class Bitwise {
  static int anAnd(int a, int b) {
    return a & b;
  }

  static int anAndClaim_IOR(int a, int b) {
    return a | b;
  }

  static int anAndClaim_IXOR(int a, int b) {
    return a ^ b;
  }

  static int anOr(int a, int b) {
    return a | b;
  }

  static int anOrClaim_IAND(int a, int b) {
    return a & b;
  }

  static int anOrClaim_IXOR(int a, int b) {
    return a ^ b;
  }

  static int aXor(int a, int b) {
    return a ^ b;
  }

  static int aXorClaim_IAND(int a, int b) {
    return a & b;
  }

  static int aXorClaim_IOR(int a, int b) {
    return a | b;
  }

  static int aLeftShift(int a, int b) {
    return a << b;
  }

  static int aLeftShiftClaim(int a, int b) {
    return a >> b;
  }

  static int aRightShift(int a, int b) {
    return a >> b;
  }

  static int aRightShiftClaim_ISHL(int a, int b) {
    return a << b;
  }

  static int aRightShiftClaim_IUSHR(int a, int b) {
    return a >>> b;
  }

  static int anUnsignedShift(int a, int b) {
    return a >>> b;
  }

  static int anUnsignedShiftClaim(int a, int b) {
    return a >> b;
  }

  static int aLongAnd(int a, int b) {
    long x = a;
    long y = b * 4294967296L;
    return Long.hashCode(x & y);
  }

  static int aLongAndClaim_LOR(int a, int b) {
    long x = a;
    long y = b * 4294967296L;
    return Long.hashCode(x | y);
  }

  static int aLongAndClaim_LXOR(int a, int b) {
    long x = a;
    long y = b * 4294967296L;
    return Long.hashCode(x ^ y);
  }

  static int aLongShift(int a, int b) {
    long x = a * 1099511627776L;
    return Long.hashCode(x >>> b);
  }

  static int aLongShiftClaim(int a, int b) {
    long x = a * 1099511627776L;
    return Long.hashCode(x >> b);
  }

  static boolean bothBooleans(int a, int b) {
    boolean x = a > 0;
    boolean y = b > 0;
    return x & y;
  }

  static boolean bothBooleansClaim_IOR(int a, int b) {
    boolean x = a > 0;
    boolean y = b > 0;
    return x | y;
  }

  static boolean bothBooleansClaim_IXOR(int a, int b) {
    boolean x = a > 0;
    boolean y = b > 0;
    return x ^ y;
  }

  /// Setting bits: never flipping them, as Rust's `|=` never becomes `^=`.
  static int setsBits(int a, int b) {
    int flags = a;
    flags |= b;
    return flags;
  }

  static int setsBitsClaim(int a, int b) {
    int flags = a;
    flags &= b;
    return flags;
  }

  static int clearsBits(int a, int b) {
    int flags = a;
    flags &= ~b;
    return flags;
  }

  static int clearsBitsClaim(int a, int b) {
    int flags = a;
    flags |= ~b;
    return flags;
  }

  /// `~a` is `a ^ -1` to javac, and isn't changed.
  static int aNot(int a, int b) {
    return ~a + b;
  }

  static int aLongNot(int a, int b) {
    return Long.hashCode(~(long) a) + b;
  }

  /// Bits of a field of the object: `bits |= b`.
  private int bits;

  static int aFieldsBits(int a, int b) {
    var holder = new Bitwise();
    holder.bits = a;
    holder.bits |= b;
    return holder.bits;
  }

  static int aFieldsBitsClaim(int a, int b) {
    var holder = new Bitwise();
    holder.bits = a;
    holder.bits &= b;
    return holder.bits;
  }
}
