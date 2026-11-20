package com.example.arid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class BackoffTest {
  @Test
  void pausesBetweenTries() throws Exception {
    int[] asked = {0};
    long start = System.nanoTime();
    assertEquals(3, Backoff.poll(() -> ++asked[0] == 3, 50));
    assertTrue(System.nanoTime() - start >= 100_000_000L, "two pauses of 50 ms");
  }
}
