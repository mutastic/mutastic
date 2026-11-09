package com.example.calls;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class OrderTest {
  @Test
  void payingGivesTheTotal() {
    assertEquals(30, new Order(30).pay());
  }
}
