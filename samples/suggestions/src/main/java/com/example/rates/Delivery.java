package com.example.rates;

/// Delivery charges: free from an order total each shop sets.
public final class Delivery {
  private final int freeFrom;

  public Delivery(int freeFrom) {
    this.freeFrom = freeFrom;
  }

  public boolean isFree(int total) {
    return total >= freeFrom;
  }
}
