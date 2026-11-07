package com.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;

class StaticInitSiteTest {
  static final int VALUE = Sites.staticInitValue(1);

  static {
    Sites.staticInitThrows(1);
  }

  @Test
  void readsNothing() {}

  @Test
  void readsWhatSetUpMade() {
    assertEquals(2, VALUE);
  }
}
