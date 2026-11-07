package com.example.lifecycle;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.*;

public class ConstructorSiteTest {
  final int value;

  public ConstructorSiteTest() {
    value = Sites.constructorValue(1);
    Sites.constructorThrows(1);
  }

  @Test
  public void readsWhatSetUpMade() {
    assertEquals(value, 2);
  }

  @Test
  public void readsNothing() {}
}
