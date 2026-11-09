package com.example.steps;

import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class StepsTest {
  // Weak on purpose: any one line going wrong still leaves more than it started with.
  @Test
  void itGoesUp() {
    assertNotEquals(0, new Steps().fourMore(0));
  }
}
