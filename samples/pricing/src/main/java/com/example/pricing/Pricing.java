package com.example.pricing;

public class Pricing {
  private final int freeShippingFrom;

  public Pricing() {
    this(50);
  }

  /// A test seam: tests set the free-shipping threshold.
  Pricing(int freeShippingFrom) {
    this.freeShippingFrom = freeShippingFrom;
  }

  /// 10% off orders of 100 or more.
  public int discount(int total) {
    if (total >= 100) {
      return total / 10;
    }
    return 0;
  }

  /// Free from the threshold up.
  public int shipping(int total) {
    if (total < freeShippingFrom) {
      return 5;
    }
    return 0;
  }
}
