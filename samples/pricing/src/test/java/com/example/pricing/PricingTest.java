package com.example.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PricingTest {
  // Only far from the edge: `>=` turning into `>` goes unnoticed.
  @Test
  void bigOrdersGetTenPercentOff() {
    assertEquals(25, new Pricing().discount(250));
  }

  @Test
  void smallOrdersPayFullPrice() {
    assertEquals(0, new Pricing().discount(50));
  }

  // Through the package-private seam, and right at the edge.
  @Test
  void shippingIsFreeFromTheThreshold() {
    var pricing = new Pricing(40);
    assertEquals(0, pricing.shipping(40));
    assertEquals(5, pricing.shipping(39));
  }
}
