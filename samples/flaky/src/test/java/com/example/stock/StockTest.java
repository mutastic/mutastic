package com.example.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class StockTest {
  @Test
  void reordersAtTheReorderLevel() {
    var stock = new Stock();
    assertTrue(stock.needsReorder(5, 5));
    assertFalse(stock.needsReorder(6, 5));
  }

  // Flaky on purpose: fails the first time it runs in a JVM, and passes after, like a test
  // that depends on a warm cache or on timing.
  @Test
  void aSaleReducesStock() {
    if (System.getProperty("stock.warm") == null) {
      System.setProperty("stock.warm", "true");
      throw new IllegalStateException("cold cache");
    }
    assertEquals(7, new Stock().afterSale(10, 3));
  }

  // Weak and flaky on purpose: checks nothing about the result, and fails every second time it
  // runs in a JVM, like a test that races a background thread. Any catch it makes is luck.
  @Test
  void restockingWorks() {
    int runs = Integer.getInteger("stock.restock.runs", 0) + 1;
    System.setProperty("stock.restock.runs", String.valueOf(runs));
    if (runs % 2 == 0) {
      throw new IllegalStateException("lost the race");
    }
    new Stock().restock(10, 5);
  }
}
