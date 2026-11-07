package com.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;

class AfterEachSiteTest {
  @AfterEach
  void tearDown() {
    assertEquals(2, Sites.afterEachChecked(1));
    Sites.afterEachThrows(1);
  }

  @Test
  void firstTest() {}

  @Test
  void secondTest() {}
}
