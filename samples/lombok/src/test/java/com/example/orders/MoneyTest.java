package com.example.orders;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

// Two currencies are left untested on purpose. Each line the sample's cards show is reached by one
// test, or caught by every test that reaches it: tests that reach it run fastest first, so which
// one catches it first would move with their timings.
class MoneyTest {
  @Test
  void amountsInOneCurrencyAdd() {
    assertEquals(new Money(350, "EUR"), new Money(100, "EUR").plus(new Money(250, "EUR")));
  }
}
