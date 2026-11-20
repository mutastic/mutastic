package com.example.arid;

/** An amount typed in by a person, who's told what's wrong with it. */
public final class Amounts {
  private Amounts() {}

  public static long parse(String text) {
    long amount = Long.parseLong(text);
    if (amount < 0) {
      throw new IllegalArgumentException("amount must not be negative");
    }
    return amount;
  }
}
