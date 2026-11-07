package com.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;

class BeforeEachSiteTest {
  int value;

  @BeforeEach
  void setUp() {
    value = Sites.beforeEachValue(1);
    Sites.beforeEachThrows(1);
  }

  @Test
  void readsWhatSetUpMade() {
    assertEquals(2, value);
  }

  @Test
  void readsNothing() {}
}
