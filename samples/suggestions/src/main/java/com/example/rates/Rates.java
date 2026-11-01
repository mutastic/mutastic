package com.example.rates;

import java.util.Random;

public final class Rates {
  private Rates() {}

  /// Late fees: none for the first 7 days, then 5 plus 2 per day after.
  public static long lateFee(long daysLate) {
    if (daysLate <= 7) {
      return 0;
    }
    return 5 + (daysLate - 7) * 2;
  }

  /// Whether a balance is in credit: zero isn't.
  public static boolean inCredit(int balance) {
    return balance > 0;
  }

  /// A small random wobble on a price, for load testing: no test can pin its answer.
  public static int jitter(int price) {
    return price + new Random().nextInt(5);
  }
}
