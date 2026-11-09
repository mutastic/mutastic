package com.example.arithmetic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class AccountTest {
  @Test
  void noOverdraft() {
    var account = new Account(0);
    assertTrue(account.overdrawn(-5));
    assertFalse(account.overdrawn(0));
    assertFalse(account.overdrawn(5));
  }
}
