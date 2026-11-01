package com.example.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FeesTest {
  // The build sets FEE_RATE=2 for its tests: without it, this fails.
  @Test
  void chargesTheRatePerTenStarted() {
    assertEquals(0, new Fees().fee(5));
    assertEquals(6, new Fees().fee(25));
  }
}
