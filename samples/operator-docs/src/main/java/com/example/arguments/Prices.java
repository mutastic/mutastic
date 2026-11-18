package com.example.arguments;

public final class Prices {
  private Prices() {}

  /// The amount as a receipt shows it: always two decimal places.
  public static String receipt(double amount) {
    return Amounts.shown(amount, true);
  }
}
