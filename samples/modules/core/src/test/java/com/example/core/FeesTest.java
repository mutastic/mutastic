package com.example.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class FeesTest {
  // Nothing here checks 10 itself: `<` becoming `<=` goes unnoticed by :core's own tests.
  @Test
  void chargesPerTenStarted() {
    assertEquals(0, new Fees().fee(5));
    assertEquals(3, new Fees().fee(25));
  }
}
