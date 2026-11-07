package com.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;

class NestedSiteTest {
  static int outerValue;

  @BeforeAll
  static void setUp() {
    outerValue = Sites.nestedOuterValue(1);
    Sites.nestedOuterThrows(1);
  }

  @AfterAll
  static void tearDown() {
    assertEquals(2, Sites.nestedOuterAfterChecked(1));
    Sites.nestedOuterAfterThrows(1);
  }

  @Test
  void outerReadsNothing() {}

  @Nested
  class Inner {
    int eachValue;

    @BeforeEach
    void setUp() {
      eachValue = Sites.nestedEachValue(1);
      Sites.nestedEachThrows(1);
    }

    @Test
    void readsWhatSetUpMade() {
      assertEquals(2, outerValue);
      assertEquals(2, eachValue);
    }

    @Test
    void readsNothing() {}
  }
}
