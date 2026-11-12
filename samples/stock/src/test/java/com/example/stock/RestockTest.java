package com.example.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RestockTest {
  @Test
  void ordersNoneWhileSomeAreLeft() {
    assertEquals(0, Restock.toOrder(3, 10));
    assertEquals(0, Restock.toOrderValidated(3, 10));
  }

  @Test
  void ordersTheTargetWhenNoneAreLeft() {
    assertEquals(10, Restock.toOrder(0, 10));
    assertEquals(10, Restock.toOrderValidated(0, 10));
  }
}
