package com.example.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class CustomerTest {
  @Test
  void manyPointsMakeGold() {
    var customer = new Customer("Ada");
    customer.setLoyaltyPoints(1500);
    assertEquals("gold", customer.tier());
  }

  @Test
  void aCustomerHasAName() {
    assertThrows(NullPointerException.class, () -> new Customer(null));
  }
}
