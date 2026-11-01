package com.example.core;

public class Fees {
  /// Free under 10, then 1 per 10 started.
  public int fee(int amount) {
    if (amount < 10) {
      return 0;
    }
    return (amount + 9) / 10;
  }
}
