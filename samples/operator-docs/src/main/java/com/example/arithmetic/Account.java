package com.example.arithmetic;

public final class Account {
  private final int overdraft;

  public Account(int overdraft) {
    this.overdraft = overdraft;
  }

  /// Whether a balance is below what the account may go down to.
  public boolean overdrawn(int balance) {
    return balance < -overdraft;
  }
}
