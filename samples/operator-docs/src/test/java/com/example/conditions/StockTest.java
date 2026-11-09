package com.example.conditions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StockTest {
  @Test
  void restockWhenLow() {
    var stock = new Stock(5);
    assertTrue(stock.needsRestock(2));
    assertFalse(stock.needsRestock(5));
  }
}
