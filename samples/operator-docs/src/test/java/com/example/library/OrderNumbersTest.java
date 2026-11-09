package com.example.library;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OrderNumbersTest {
  @Test
  void digitsAreAnOrderNumber() {
    assertTrue(OrderNumbers.isValid("123"));
    assertFalse(OrderNumbers.isValid("abc"));
  }
}
