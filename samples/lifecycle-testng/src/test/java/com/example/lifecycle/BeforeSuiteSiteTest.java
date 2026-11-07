package com.example.lifecycle;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.*;

public class BeforeSuiteSiteTest {
  int value;

  @BeforeSuite
  public void setUp() {
    value = Sites.beforeSuiteValue(1);
    Sites.beforeSuiteThrows(1);
  }

  @Test
  public void readsWhatSetUpMade() {
    assertEquals(value, 2);
  }

  @Test
  public void readsNothing() {}
}
