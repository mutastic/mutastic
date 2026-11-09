package com.example.arguments;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ScoresTest {
  @Test
  void theBestComesFirst() {
    assertEquals(9, Scores.bestOfThree(9, 2, 1));
  }
}
