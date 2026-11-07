package com.example.lifecycle;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.*;

public class AfterSuiteSiteTest {
  @AfterSuite
  public void tearDown() {
    assertEquals(Sites.afterSuiteChecked(1), 2);
    Sites.afterSuiteThrows(1);
  }

  @Test
  public void firstTest() {}

  @Test
  public void secondTest() {}
}
