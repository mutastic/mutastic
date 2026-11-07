package com.example.lifecycle;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.*;

public class AfterMethodSiteTest {
  @AfterMethod
  public void tearDown() {
    assertEquals(Sites.afterMethodChecked(1), 2);
    Sites.afterMethodThrows(1);
  }

  @Test
  public void firstTest() {}

  @Test
  public void secondTest() {}
}
