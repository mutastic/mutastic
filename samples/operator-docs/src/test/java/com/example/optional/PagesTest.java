package com.example.optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class PagesTest {
  @Test
  void theStandardSize() {
    assertEquals(20, new Pages(20).size(null));
  }

  @Test
  void aChosenSize() {
    assertEquals(20, new Pages(20).size(20));
  }
}
