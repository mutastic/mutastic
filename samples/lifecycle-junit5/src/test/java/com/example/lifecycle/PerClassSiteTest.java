package com.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PerClassSiteTest {
  int value;
  int fromField = Sites.perClassFieldValue(1);
  int fromConstructor;

  PerClassSiteTest() {
    fromConstructor = Sites.perClassConstructorValue(1);
    Sites.perClassConstructorThrows(1);
    Sites.perClassFieldThrows(1);
  }

  @BeforeAll
  void setUp() {
    value = Sites.perClassValue(1);
    Sites.perClassThrows(1);
  }

  @AfterAll
  void tearDown() {
    assertEquals(2, Sites.perClassAfterChecked(1));
    Sites.perClassAfterThrows(1);
  }

  @Test
  void readsWhatSetUpMade() {
    assertEquals(2, value);
    assertEquals(2, fromField);
    assertEquals(2, fromConstructor);
  }

  @Test
  void readsNothing() {}
}
