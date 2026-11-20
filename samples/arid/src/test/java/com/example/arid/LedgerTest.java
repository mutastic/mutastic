package com.example.arid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class LedgerTest {
  @Test
  void refusesANegativeBalance() {
    assertThrows(AssertionError.class, () -> Ledger.settle(-1));
    assertEquals(3, Ledger.settle(3));
  }
}
