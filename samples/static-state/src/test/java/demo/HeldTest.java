package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/// Holds a count behind a plain Object.
class HeldTest {
  private static final Object HELD = new int[1];

  @Test
  void holdsOne() {
    var count = (int[]) HELD;
    count[0]++;
    assertEquals(1, count[0]);
    assertEquals(190, Shop.held(200));
  }
}
