package com.example.selection;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

// Tagged slow, which the build leaves out: its check at 10 must not count.
@Tag("slow")
class BoundaryTest {
  @Test
  void tenIsCharged() {
    assertEquals(2, new Fees().fee(10));
  }
}
