package com.example.streams;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class FormsTest {
  @Test
  void aBlankAnswer() {
    assertTrue(Forms.anyBlank(List.of(" ")));
    assertFalse(Forms.anyBlank(List.of("yes")));
  }
}
