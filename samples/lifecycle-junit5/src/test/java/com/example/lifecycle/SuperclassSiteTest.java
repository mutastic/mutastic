package com.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;

class SuperclassSiteTest extends SuperclassBase {
  @Test
  void readsWhatSetUpMade() {
    assertEquals(2, allValue);
    assertEquals(2, eachValue);
  }

  @Test
  void readsNothing() {}
}
