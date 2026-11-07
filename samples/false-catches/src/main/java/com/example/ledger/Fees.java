package com.example.ledger;

public class Fees {
  /// The fee on an amount, a whole percent of it.
  public static int fee(int amount, int percent) {
    return amount * percent / 100;
  }

  /// No fee on an amount at or over the threshold.
  public static boolean waived(int amount, int threshold) {
    return amount >= threshold;
  }
}
