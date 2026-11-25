package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class OuterTest {
  /// Counts its runs in a static of a nested class.
  @Nested
  class Twice {
    private static int runs;

    @Test
    void runsOnce() {
      runs++;
      assertEquals(1, runs);
      assertEquals(190, Shop.nested(200));
    }
  }
}
