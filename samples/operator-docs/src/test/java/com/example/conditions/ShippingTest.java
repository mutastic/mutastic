package com.example.conditions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ShippingTest {
  @Test
  void shipsWhenPaidAndInStock() {
    assertTrue(Shipping.canShip(true, true));
    assertFalse(Shipping.canShip(false, false));
  }
}
