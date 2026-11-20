package com.example.arid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class WorkerTest {
  @Test
  void waitsForTheWork() throws Exception {
    assertEquals(5, Worker.result(WorkerTest::slowFive, 5));
  }

  /** Work that takes a moment, as real work does. */
  private static long slowFive() {
    try {
      Thread.sleep(200);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    return 5;
  }
}
