package com.example.lifecycle;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.*;

public class BeforeClassSiteTest {
  int value;

  @BeforeClass
  public void setUp() {
    value = Sites.beforeClassValue(1);
    Sites.beforeClassThrows(1);
  }

  @Test
  public void readsWhatSetUpMade() {
    assertEquals(value, 2);
  }

  @Test
  public void readsNothing() {}
}
