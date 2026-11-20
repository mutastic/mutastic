package com.example.arid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CustomerTest {
  @Test
  void keepsTheNameItIsGiven() {
    var customer = new Customer();
    customer.setName("Ada");
    assertEquals("Ada", customer.getName());
  }
}
