package com.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;

class FieldSiteTest {
  final int value = Sites.fieldValue(1);
  final int thrown = Sites.fieldThrows(1);

  @Test
  void readsWhatSetUpMade() {
    assertEquals(2, value);
  }

  @Test
  void readsNothing() {}
}
