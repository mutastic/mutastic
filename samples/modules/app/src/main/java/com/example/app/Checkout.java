package com.example.app;

import com.example.core.Fees;

public class Checkout {
  private final Fees fees = new Fees();

  public int total(int amount) {
    return amount + fees.fee(amount);
  }
}
