package com.example.calls;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class TipsTest {
  @Test
  void noTip() {
    assertEquals(10, Tips.withTip(10, 0));
  }
}
