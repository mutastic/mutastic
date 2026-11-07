package com.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.*;

@ExtendWith(ExtendWithSiteTest.Callbacks.class)
class ExtendWithSiteTest {
  static int allValue;
  static int eachValue;

  static class Callbacks
      implements BeforeAllCallback, AfterAllCallback, BeforeEachCallback, AfterEachCallback {
    @Override
    public void beforeAll(ExtensionContext context) {
      allValue = Sites.extendWithAllValue(1);
      Sites.extendWithAllThrows(1);
    }

    @Override
    public void afterAll(ExtensionContext context) {
      assertEquals(2, Sites.extendWithAfterAllChecked(1));
      Sites.extendWithAfterAllThrows(1);
    }

    @Override
    public void beforeEach(ExtensionContext context) {
      eachValue = Sites.extendWithEachValue(1);
      Sites.extendWithEachThrows(1);
    }

    @Override
    public void afterEach(ExtensionContext context) {
      assertEquals(2, Sites.extendWithAfterEachChecked(1));
      Sites.extendWithAfterEachThrows(1);
    }
  }

  @Test
  void readsWhatSetUpMade() {
    assertEquals(2, allValue);
    assertEquals(2, eachValue);
  }

  @Test
  void readsNothing() {}
}
