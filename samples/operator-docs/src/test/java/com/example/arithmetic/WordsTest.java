package com.example.arithmetic;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class WordsTest {
  @Test
  void shortListsAreKeptWhole() {
    assertEquals(List.of("tea", "milk"), Words.upTo(List.of("tea", "milk"), 5));
  }
}
