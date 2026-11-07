package com.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;

class BeforeAllSiteTest {
  static int value;

  @BeforeAll
  static void setUp() {
    value = Sites.beforeAllValue(1);
    Sites.beforeAllThrows(1);
  }

  @Test
  void readsWhatSetUpMade() {
    assertEquals(2, value);
  }

  @Test
  void readsNothing() {}
}
