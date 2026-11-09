package com.example.arguments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CsvTest {
  @Test
  void theFirstFieldEndsAtItsComma() {
    assertEquals(1, Csv.nextComma("a,b", 0));
  }
}
