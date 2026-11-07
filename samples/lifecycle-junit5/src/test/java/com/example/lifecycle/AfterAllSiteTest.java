package com.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;

class AfterAllSiteTest {
  @AfterAll
  static void tearDown() {
    assertEquals(2, Sites.afterAllChecked(1));
    Sites.afterAllThrows(1);
  }

  @Test
  void firstTest() {}

  @Test
  void secondTest() {}
}
