package com.example.conditions;

/// Free delivery from a total the shop sets.
public final class Delivery {
  private final int freeFrom;

  public Delivery(int freeFrom) {
    this.freeFrom = freeFrom;
  }

  public boolean isFree(int total) {
    return total >= freeFrom;
  }
}
