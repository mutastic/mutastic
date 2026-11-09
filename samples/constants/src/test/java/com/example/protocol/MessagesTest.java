package com.example.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayDeque;
import java.util.List;
import org.junit.jupiter.api.Test;

class MessagesTest {
  // No message ends the game: the exit code is never needed.
  @Test
  void countsEveryMessage() {
    assertEquals(2, new Messages().beforeExit(new ArrayDeque<>(List.of("a", "b"))));
  }

  // Only numbers: nothing checks a type that isn't one.
  @Test
  void numbersAreNumeric() {
    assertTrue(new Messages().numeric(Integer.class));
    assertTrue(new Messages().numeric(long.class));
  }
}
