package com.example.leftovers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class SharedTest {
  private static final Path TEMP = Path.of(System.getProperty("java.io.tmpdir"));

  @Test
  void stashGivesTheLength() throws Exception {
    assertEquals(5, Shared.stash(TEMP, "hello"));
  }

  @Test
  void measureGivesTheSize() throws Exception {
    assertEquals(5, Shared.measure(TEMP, "hello"));
  }
}
