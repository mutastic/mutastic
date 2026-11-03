package com.example.billing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BillingIT {
  // At the edge: these catch `>=` becoming `>`.
  @Test
  void theThirtiethDayIsLate() {
    assertTrue(new Billing().late(30));
    assertFalse(new Billing().late(29));
  }
}
