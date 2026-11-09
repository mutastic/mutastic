package com.example.control;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class ReaderTest {
  @Test
  void aFreshRead() {
    assertEquals("fresh", Reader.load(() -> "fresh", "cached"));
  }

  @Test
  void aFailedReadFallsBackToTheCache() {
    assertEquals("cached", Reader.load(ReaderTest::unreadable, "cached"));
  }

  private static String unreadable() throws IOException {
    throw new IOException("the file is gone");
  }
}
