package com.example.arid;

/** An amount of money, shown to customers as pounds and pence. */
public final class Money {
  private final long pence;

  public Money(long pence) {
    this.pence = pence;
  }

  @Override
  public String toString() {
    return "£" + pence / 100 + "." + String.format("%02d", pence % 100);
  }
}
