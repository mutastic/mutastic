package com.example.library;

public final class Discounts {
  private final int cap;

  public Discounts(int cap) {
    this.cap = cap;
  }

  /// Never more off than the cap.
  public int capped(int percent) {
    return Math.min(percent, cap);
  }
}
