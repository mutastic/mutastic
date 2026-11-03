package com.example.rates;

public class Rates {
  /// A fee from 30 days late.
  public int lateFee(int days) {
    if (days >= 30) {
      return 25;
    }
    return 0;
  }
}
