package com.example.billing;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BillingTest {
  // Far from the edge: `>=` becoming `>` goes unnoticed here.
  @Test
  void aMonthIsLate() {
    assertTrue(new Billing().late(45));
    assertFalse(new Billing().late(5));
  }
}
