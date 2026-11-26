package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/// Counts its runs in a static: one run of the build runs it once.
class CountedTest {
  private static int runs;

  @Test
  void runsOnce() {
    runs++;
    assertEquals(1, runs);
    assertEquals(190, Shop.counted(200));
  }
}
