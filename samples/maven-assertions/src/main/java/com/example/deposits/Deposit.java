package com.example.deposits;

/// A deposit in cents, never below zero: an `assert` holds it, so `valid`'s mutants are caught only
/// when the tests run with assertions on, as Surefire runs them.
public final class Deposit {
  private final long cents;

  public Deposit(long cents) {
    assert valid(cents) : "a deposit can't be negative";
    this.cents = cents;
  }

  static boolean valid(long cents) {
    return cents >= 0;
  }

  public long cents() {
    return cents;
  }
}
