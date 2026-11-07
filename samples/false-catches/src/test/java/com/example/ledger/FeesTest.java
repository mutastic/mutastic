package com.example.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class FeesTest {
  // Broken on purpose: passes only when ConfigurationTest has run first in the same JVM, as it
  // does when the whole suite runs. On its own, the percent is 0, the fee is 0, and it fails.
  @Test
  void chargesTheConfiguredPercent() {
    assertEquals(10, Fees.fee(100, Settings.percent));
  }

  @Test
  void aFeeIsNeverNegative() {
    assertTrue(Fees.fee(100, 10) >= 0);
  }

  @Test
  void waivesFromTheThreshold() {
    assertTrue(Fees.waived(100, 100));
    assertFalse(Fees.waived(99, 100));
  }
}
