package com.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;

abstract class SuperclassBase {
  static int allValue;
  int eachValue;

  @BeforeAll
  static void setUpAll() {
    allValue = Sites.superclassAllValue(1);
    Sites.superclassAllThrows(1);
  }

  @BeforeEach
  void setUp() {
    eachValue = Sites.superclassEachValue(1);
    Sites.superclassEachThrows(1);
  }

  @AfterAll
  static void tearDownAll() {
    assertEquals(2, Sites.superclassAfterChecked(1));
    Sites.superclassAfterThrows(1);
  }
}
