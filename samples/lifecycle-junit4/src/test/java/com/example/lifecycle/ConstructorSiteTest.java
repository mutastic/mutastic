package com.example.lifecycle;

import static org.junit.Assert.assertEquals;

import org.junit.*;

public class ConstructorSiteTest {
  final int value;

  public ConstructorSiteTest() {
    value = Sites.constructorValue(1);
    Sites.constructorThrows(1);
  }

  @Test
  public void readsWhatSetUpMade() {
    assertEquals(2, value);
  }

  @Test
  public void readsNothing() {}
}
