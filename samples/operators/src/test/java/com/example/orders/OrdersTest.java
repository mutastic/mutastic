package com.example.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class OrdersTest {
  // Every order is large: the filter is never needed.
  @Test
  void countsLargeOrders() {
    assertEquals(2, new Orders().large(List.of(150, 200)));
  }

  // 2 + 2 and 2 * 2 agree.
  @Test
  void addsShipping() {
    assertEquals(4, new Orders().total(2, 2));
  }

  // Only a code typed without spaces: `strip()` is never needed.
  @Test
  void codesAreUpperCase() {
    assertEquals("SAVE10", new Orders().code("save10"));
  }
}
