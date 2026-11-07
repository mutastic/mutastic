package com.example.lifecycle;

import static org.junit.Assert.assertEquals;

import org.junit.*;

public class AfterSiteTest {
  @After
  public void tearDown() {
    assertEquals(2, Sites.afterChecked(1));
    Sites.afterThrows(1);
  }

  @Test
  public void firstTest() {}

  @Test
  public void secondTest() {}
}
