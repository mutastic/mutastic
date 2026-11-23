package com.example.fastpath;

/** Reads a whole number written in decimal digits. */
public final class Digits {
  private Digits() {}

  /** The number `digits` spells, which has only the digits 0 to 9. */
  public static long read(String digits) {
    if (digits.length() > 9) {
      return readCarefully(digits);
    }
    int value = 0;
    for (int at = 0; at < digits.length(); at++) {
      value = value * 10 + (digits.charAt(at) - '0');
    }
    return value;
  }

  /** At any length, in a long that throws rather than overflows. */
  static long readCarefully(String digits) {
    long value = 0;
    for (int at = 0; at < digits.length(); at++) {
      value = Math.addExact(Math.multiplyExact(value, 10), digits.charAt(at) - '0');
    }
    return value;
  }
}
