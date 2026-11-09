package com.example.returns;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class QuantitiesTest {
  @Test
  void aQuantity() {
    assertEquals(3, Quantities.parse("3"));
  }

  @Test
  void noQuantityIsAnError() {
    assertThrows(IllegalArgumentException.class, () -> Quantities.parse(""));
  }
}
