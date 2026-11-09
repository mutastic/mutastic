package com.example.arithmetic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CheckoutTest {
  @Test
  void freeShipping() {
    assertEquals(30, Checkout.total(30, 0));
  }
}
