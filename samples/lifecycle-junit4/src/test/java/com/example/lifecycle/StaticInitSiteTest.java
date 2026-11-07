package com.example.lifecycle;

import static org.junit.Assert.assertEquals;

import org.junit.*;

public class StaticInitSiteTest {
  static final int VALUE = Sites.staticInitValue(1);

  static {
    Sites.staticInitThrows(1);
  }

  @Test
  public void readsNothing() {}

  @Test
  public void readsWhatSetUpMade() {
    assertEquals(2, VALUE);
  }
}
