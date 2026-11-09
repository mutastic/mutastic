package com.example.arithmetic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WheelTest {
  @Test
  void threeStepsOn() {
    assertEquals(3, new Wheel(10).slotAfter(3));
  }
}
