package com.example.returns;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class LoyaltyTest {
  @Test
  void smallSpendsEarnNothing() {
    assertEquals(0, new Loyalty(10).points(5));
  }
}
