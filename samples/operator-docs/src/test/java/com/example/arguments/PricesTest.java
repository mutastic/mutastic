package com.example.arguments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PricesTest {
  @Test
  void aPriceInPence() {
    assertEquals("2.25", Prices.receipt(2.25));
  }
}
