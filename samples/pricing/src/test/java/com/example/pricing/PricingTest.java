package com.example.pricing;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.example.testing.Orders;
import java.util.Properties;
import org.junit.jupiter.api.Test;

class PricingTest {
  // Only far from the edge: `>=` turning into `>` goes unnoticed.
  // A helper from another test package, and a test resource: mutastic must find both, as
  // Gradle's one test classpath does.
  @Test
  void bigOrdersGetTenPercentOff() {
    assertEquals(25, new Pricing().discount(Orders.large()));
  }

  @Test
  void smallOrdersPayFullPrice() throws Exception {
    var orders = new Properties();
    try (var in = getClass().getResourceAsStream("/orders.properties")) {
      orders.load(in);
    }
    assertEquals(0, new Pricing().discount(Integer.parseInt(orders.getProperty("small"))));
  }

  // Through the package-private seam, and right at the edge.
  @Test
  void shippingIsFreeFromTheThreshold() {
    var pricing = new Pricing(40);
    assertEquals(0, pricing.shipping(40));
    assertEquals(5, pricing.shipping(39));
  }
}
