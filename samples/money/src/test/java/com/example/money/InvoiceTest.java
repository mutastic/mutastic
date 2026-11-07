package com.example.money;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class InvoiceTest {
  private static BigDecimal money(String amount) {
    return new BigDecimal(amount);
  }

  @Test
  void aTotalAddsTheTax() {
    assertEquals(money("12"), Invoice.total(money("10"), money("2")));
  }

  @Test
  void aRefundRuns() {
    Invoice.refund(money("10"), money("2"));
  }

  @Test
  void theCheapestWithEqualPrices() {
    assertEquals(money("3"), Invoice.cheapest(money("3"), money("3")));
  }

  @Test
  void aPositiveBalanceIsOwed() {
    assertEquals(money("3"), Invoice.owed(money("3")));
  }

  @Test
  void roundingKeepsTwoPlaces() {
    assertEquals(money("1.24"), Invoice.rounded(money("1.235")));
  }

  @Test
  void aRateOfOne() {
    assertEquals(0, money("5").compareTo(Invoice.scaled(money("5"), BigDecimal.ONE)));
  }
}
