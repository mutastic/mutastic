package com.example.arid;

/** A balance that is never negative, which an assert checks. */
public final class Ledger {
  private Ledger() {}

  public static long settle(long balance) {
    assert balance >= 0 : "negative balance";
    return balance;
  }
}
