package com.example.arithmetic;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class PostageTest {
  @Test
  void heavyFromJustOver500() {
    assertFalse(Postage.isHeavy(100));
    assertFalse(Postage.isHeavy(500));
    assertTrue(Postage.isHeavy(1000));
  }
}
