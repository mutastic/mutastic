package com.example.arid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class MoneyTest {
  @Test
  void showsPoundsAndPence() {
    assertEquals("£1.05", new Money(105).toString());
  }
}
