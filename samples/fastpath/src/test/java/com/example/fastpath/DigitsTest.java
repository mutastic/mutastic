package com.example.fastpath;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class DigitsTest {
  @Test
  void readsAFewDigits() {
    assertEquals(7, Digits.read("7"));
    assertEquals(305, Digits.read("305"));
  }

  @Test
  void readsNineDigits() {
    assertEquals(987_654_321, Digits.read("987654321"));
  }

  @Test
  void readsMoreThanAnIntHolds() {
    assertEquals(12_345_678_901L, Digits.read("12345678901"));
  }

  @Test
  void refusesMoreThanALongHolds() {
    assertThrows(ArithmeticException.class, () -> Digits.read("99999999999999999999"));
  }
}
