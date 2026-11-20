package com.example.arid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class HeadersTest {
  @Test
  void keepsTheColonsInTheValue() {
    assertEquals("http://example.com:8080", Headers.value("Location: http://example.com:8080"));
  }
}
