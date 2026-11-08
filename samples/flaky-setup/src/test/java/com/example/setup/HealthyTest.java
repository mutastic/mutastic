package com.example.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HealthyTest {
  @Test
  void doubles() {
    assertEquals(6, Prices.doubled(3));
  }
}
