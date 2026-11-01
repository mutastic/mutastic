package com.example.app;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CheckoutTest {
  // The edge :core's own tests leave out: 10 pays a fee of 1.
  @Test
  void tenPaysAFee() {
    assertEquals(11, new Checkout().total(10));
  }
}
