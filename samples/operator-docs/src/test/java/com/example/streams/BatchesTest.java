package com.example.streams;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class BatchesTest {
  @Test
  void aFewOrdersGoTogether() {
    assertEquals(List.of(List.of("a", "b")), Batches.of(List.of("a", "b"), 3));
  }
}
