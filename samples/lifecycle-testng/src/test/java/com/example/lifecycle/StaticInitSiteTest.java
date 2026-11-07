package com.example.lifecycle;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.*;

public class StaticInitSiteTest {
  static final int VALUE = Sites.staticInitValue(1);

  static {
    Sites.staticInitThrows(1);
  }

  @Test
  public void readsNothing() {}

  @Test
  public void readsWhatSetUpMade() {
    assertEquals(VALUE, 2);
  }
}
