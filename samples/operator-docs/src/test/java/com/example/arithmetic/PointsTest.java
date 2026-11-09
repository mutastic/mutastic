package com.example.arithmetic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PointsTest {
  @Test
  void pointsAddUp() {
    assertEquals(3, Points.add(1, 2));
  }
}
