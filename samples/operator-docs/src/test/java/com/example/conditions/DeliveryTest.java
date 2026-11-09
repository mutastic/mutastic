package com.example.conditions;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class DeliveryTest {
  @Test
  void smallOrdersPayForDelivery() {
    var delivery = new Delivery(50);
    assertFalse(delivery.isFree(20));
    assertTrue(delivery.isFree(80));
  }
}
