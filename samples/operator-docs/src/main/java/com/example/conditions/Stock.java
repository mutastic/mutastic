package com.example.conditions;

/// Time to restock once stock falls below the low mark.
public final class Stock {
  private final int low;

  public Stock(int low) {
    this.low = low;
  }

  public boolean needsRestock(int stock) {
    return stock < low;
  }
}
