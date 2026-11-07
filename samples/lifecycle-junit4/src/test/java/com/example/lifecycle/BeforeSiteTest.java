package com.example.lifecycle;

import static org.junit.Assert.assertEquals;

import org.junit.*;

public class BeforeSiteTest {
  int value;

  @Before
  public void setUp() {
    value = Sites.beforeValue(1);
    Sites.beforeThrows(1);
  }

  @Test
  public void readsWhatSetUpMade() {
    assertEquals(2, value);
  }

  @Test
  public void readsNothing() {}
}
