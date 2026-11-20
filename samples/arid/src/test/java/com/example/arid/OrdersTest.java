package com.example.arid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class OrdersTest {
  @Test
  void refusesANullOrder() {
    var orders = new Orders();
    assertThrows(NullPointerException.class, () -> orders.add(null));
    assertEquals(0, orders.size());
  }
}
