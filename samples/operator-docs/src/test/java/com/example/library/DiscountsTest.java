package com.example.library;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DiscountsTest {
  @Test
  void halfOffAtMost() {
    assertEquals(50, new Discounts(50).capped(50));
  }
}
