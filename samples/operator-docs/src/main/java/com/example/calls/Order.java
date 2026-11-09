package com.example.calls;

public final class Order {
  private final int total;
  private boolean paid;

  public Order(int total) {
    this.total = total;
  }

  /// Marks the order paid, and gives what was due.
  public int pay() {
    this.paid = true;
    return total;
  }

  public boolean isPaid() {
    return paid;
  }
}
