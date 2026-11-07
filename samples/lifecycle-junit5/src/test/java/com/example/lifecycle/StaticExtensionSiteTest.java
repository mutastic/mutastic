package com.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;

class StaticExtensionSiteTest {
  static int value;
  static int beforeValue;

  static class Made implements BeforeAllCallback, AfterAllCallback {
    Made(int x) {
      value = Sites.staticExtensionValue(x);
      Sites.staticExtensionThrows(x);
    }

    @Override
    public void beforeAll(ExtensionContext context) {
      beforeValue = Sites.staticExtensionCallbackValue(1);
      Sites.staticExtensionCallbackThrows(1);
    }

    @Override
    public void afterAll(ExtensionContext context) {
      assertEquals(2, Sites.staticExtensionAfterChecked(1));
      Sites.staticExtensionAfterThrows(1);
    }
  }

  @RegisterExtension static Made made = new Made(1);

  @Test
  void readsWhatSetUpMade() {
    assertEquals(2, value);
    assertEquals(2, beforeValue);
  }

  @Test
  void readsNothing() {}
}
