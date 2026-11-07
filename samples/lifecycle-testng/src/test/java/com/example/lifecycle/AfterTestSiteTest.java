package com.example.lifecycle;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.*;

public class AfterTestSiteTest {
  @AfterTest
  public void tearDown() {
    assertEquals(Sites.afterTestChecked(1), 2);
    Sites.afterTestThrows(1);
  }

  @Test
  public void firstTest() {}

  @Test
  public void secondTest() {}
}
