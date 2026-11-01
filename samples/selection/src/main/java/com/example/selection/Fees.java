package com.example.selection;

public class Fees {
  /// Free under 10, then the rate per 10 started; the rate comes from `FEE_RATE`, 1 if unset.
  public int fee(int amount) {
    if (amount < 10) {
      return 0;
    }
    return (amount + 9) / 10 * rate();
  }

  private static int rate() {
    var rate = System.getenv("FEE_RATE");
    return rate == null ? 1 : Integer.parseInt(rate);
  }
}
