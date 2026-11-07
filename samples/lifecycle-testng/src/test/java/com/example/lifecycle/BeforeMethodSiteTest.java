package com.example.lifecycle;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.*;

public class BeforeMethodSiteTest {
  int value;

  @BeforeMethod
  public void setUp() {
    value = Sites.beforeMethodValue(1);
    Sites.beforeMethodThrows(1);
  }

  @Test
  public void readsWhatSetUpMade() {
    assertEquals(value, 2);
  }

  @Test
  public void readsNothing() {}
}
