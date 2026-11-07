package com.example.lifecycle;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.*;

public class AfterClassSiteTest {
  @AfterClass
  public void tearDown() {
    assertEquals(Sites.afterClassChecked(1), 2);
    Sites.afterClassThrows(1);
  }

  @Test
  public void firstTest() {}

  @Test
  public void secondTest() {}
}
