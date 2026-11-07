package com.example.lifecycle;

import static org.junit.Assert.assertEquals;

import org.junit.*;

public class BeforeClassSiteTest {
  static int value;

  @BeforeClass
  public static void setUp() {
    value = Sites.beforeClassValue(1);
    Sites.beforeClassThrows(1);
  }

  @Test
  public void readsWhatSetUpMade() {
    assertEquals(2, value);
  }

  @Test
  public void readsNothing() {}
}
