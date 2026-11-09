package com.example.library;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class HeadersTest {
  @Test
  void oneColumn() {
    assertEquals("name", Headers.header(List.of("name")));
  }
}
