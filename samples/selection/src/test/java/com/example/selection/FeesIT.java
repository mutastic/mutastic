package com.example.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// An integration test, which the build's test task excludes by name.
class FeesIT {
  @Test
  void tenIsCharged() {
    assertEquals(2, new Fees().fee(10));
  }
}
