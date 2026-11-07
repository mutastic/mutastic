package com.example.lifecycle;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.*;

public class BeforeTestSiteTest {
  int value;

  @BeforeTest
  public void setUp() {
    value = Sites.beforeTestValue(1);
    Sites.beforeTestThrows(1);
  }

  @Test
  public void readsWhatSetUpMade() {
    assertEquals(value, 2);
  }

  @Test
  public void readsNothing() {}
}
