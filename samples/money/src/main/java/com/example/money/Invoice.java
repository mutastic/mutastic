package com.example.money;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class Invoice {
  private Invoice() {}

  public static BigDecimal total(BigDecimal net, BigDecimal tax) {
    return net.add(tax);
  }

  public static BigDecimal refund(BigDecimal paid, BigDecimal kept) {
    return paid.subtract(kept);
  }

  public static BigDecimal cheapest(BigDecimal first, BigDecimal second) {
    return first.min(second);
  }

  public static BigDecimal owed(BigDecimal balance) {
    return balance.abs();
  }

  public static BigDecimal rounded(BigDecimal amount) {
    return amount.setScale(2, RoundingMode.HALF_UP);
  }

  public static BigDecimal scaled(BigDecimal amount, BigDecimal rate) {
    return amount.multiply(rate);
  }
}
