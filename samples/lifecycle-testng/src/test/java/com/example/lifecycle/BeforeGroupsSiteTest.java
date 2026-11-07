package com.example.lifecycle;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.*;

public class BeforeGroupsSiteTest {
  int value;

  @BeforeGroups("site")
  public void setUp() {
    value = Sites.beforeGroupsValue(1);
    Sites.beforeGroupsThrows(1);
  }

  @Test(groups = "site")
  public void readsWhatSetUpMade() {
    assertEquals(value, 2);
  }

  @Test(groups = "site")
  public void readsNothing() {}
}
