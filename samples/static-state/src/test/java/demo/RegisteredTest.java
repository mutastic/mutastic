package demo;

import static org.junit.jupiter.api.Assertions.assertEquals;

import demo.support.Registry;
import org.junit.jupiter.api.Test;

/// Registers itself in a registry of the tests' own, held in a static final field.
class RegisteredTest {
  private static final Registry REGISTRY = new Registry();

  @Test
  void registersOnce() {
    assertEquals(1, REGISTRY.register());
    assertEquals(190, Shop.registered(200));
  }
}
