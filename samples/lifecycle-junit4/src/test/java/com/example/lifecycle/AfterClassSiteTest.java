package com.example.lifecycle;

import static org.junit.Assert.assertEquals;

import org.junit.*;

public class AfterClassSiteTest {
  @AfterClass
  public static void tearDown() {
    assertEquals(2, Sites.afterClassChecked(1));
    Sites.afterClassThrows(1);
  }

  @Test
  public void firstTest() {}

  @Test
  public void secondTest() {}
}
