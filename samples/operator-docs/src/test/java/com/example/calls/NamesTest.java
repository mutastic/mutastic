package com.example.calls;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class NamesTest {
  @Test
  void namesInOrder() {
    assertEquals(List.of("al", "bo"), Names.sorted(List.of("al", "bo")));
  }
}
