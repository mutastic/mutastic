package com.example.deposits;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DepositTest {
  @Test
  void keepsItsCents() {
    assertEquals(250, new Deposit(250).cents());
    assertEquals(0, new Deposit(0).cents());
  }

  @Test
  void isNeverNegative() {
    assertThrows(AssertionError.class, () -> new Deposit(-1));
  }
}
