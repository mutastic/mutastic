package com.example.arguments;

import java.util.Locale;

public final class Amounts {
  private Amounts() {}

  /// The amount, with two decimal places when `fixed`, or as few as it needs.
  public static String shown(double amount, boolean fixed) {
    return fixed ? String.format(Locale.ROOT, "%.2f", amount) : String.valueOf(amount);
  }
}
