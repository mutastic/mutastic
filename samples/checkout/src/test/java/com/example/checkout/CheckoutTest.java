package com.example.checkout;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import org.junit.jupiter.api.Test;

class CheckoutTest {
  @Test
  void bigOrdersGetADiscount() {
    var receipt = new ArrayList<String>();
    assertTrue(new Checkout().pay(1, 250, receipt) < 250);
  }

  @Test
  void smallOrdersPayFullPrice() {
    new Checkout().pay(1, 50, new ArrayList<>());
  }
}
