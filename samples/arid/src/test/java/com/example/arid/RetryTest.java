package com.example.arid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class RetryTest {
  @Test
  void triesThreeTimes() {
    int[] calls = {0};
    assertEquals(3, Retry.attempts(() -> ++calls[0] == 3));
    assertEquals(3, calls[0]);
  }
}
