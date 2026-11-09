package com.example.conditions;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class LinesTest {
  @Test
  void blankLinesDontCount() {
    assertEquals(1, Lines.written(List.of("total: 3", " ")));
  }
}
