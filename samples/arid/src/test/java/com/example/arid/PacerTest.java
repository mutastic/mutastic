package com.example.arid;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PacerTest {
  @Test
  void pausesAfterEachMessage() throws Exception {
    var read = new ArrayList<String>();
    long start = System.nanoTime();
    assertEquals(2, Pacer.send(List.of("a", "b"), read::add, 1));
    assertEquals(List.of("a", "b"), read);
    assertTrue(System.nanoTime() - start >= 200_000_000L, "two pauses of a tenth of a second");
  }
}
