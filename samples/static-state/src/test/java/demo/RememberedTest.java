package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/// Remembers what the code said the first time: in one run of the build, the first time is now.
class RememberedTest {
  private static Integer remembered;

  @Test
  void saysTheSameAsBefore() {
    if (remembered == null) {
      remembered = Shop.remembered(200);
    }
    assertEquals(remembered, Shop.remembered(200));
  }
}
