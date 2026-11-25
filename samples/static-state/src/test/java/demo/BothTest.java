package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/// Tries the edge, so it catches what SharedTest only seems to.
class BothTest {
  @Test
  void aHundredIsFullPrice() {
    assertEquals(100, Shop.both(100));
    assertEquals(190, Shop.both(200));
  }
}
