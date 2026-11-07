package com.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;

class ConstructorSiteTest {
  final int value;

  ConstructorSiteTest() {
    value = Sites.constructorValue(1);
    Sites.constructorThrows(1);
  }

  @Test
  void readsWhatSetUpMade() {
    assertEquals(2, value);
  }

  @Test
  void readsNothing() {}
}
