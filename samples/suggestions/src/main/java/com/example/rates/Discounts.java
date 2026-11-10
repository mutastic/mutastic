package com.example.rates;

/// Discounts at totals the business names: one a caller can use, and one only this class can.
public final class Discounts {
  public static final int FREE_FROM = 100;
  private static final int BULK_FROM = 50;

  private Discounts() {}

  public static int discount(int total) {
    return total >= FREE_FROM ? 10 : 0;
  }

  public static int bulk(int count) {
    return BULK_FROM <= count ? 5 : 0;
  }

  /// How many columns to show a choice in: two for more than three.
  public static int columns(Integer chosen) {
    return chosen > 3 ? 2 : 1;
  }
}
