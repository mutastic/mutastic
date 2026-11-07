package com.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class NeedsAnotherTestSiteTest {
  static int left;

  @BeforeAll
  static void setUp() {
    Sites.needsAnotherTestValue(1);
    Sites.needsAnotherTestThrows(1);
  }

  @Test
  @Order(1)
  void leavesWhatTheNextNeeds() {
    left = 1;
  }

  @Test
  @Order(2)
  void needsWhatTheLastLeft() {
    assertEquals(1, left);
  }
}
