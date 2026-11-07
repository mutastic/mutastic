package com.example.lifecycle;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.*;

public class FieldSiteTest {
  final int value = Sites.fieldValue(1);
  final int thrown = Sites.fieldThrows(1);

  @Test
  public void readsWhatSetUpMade() {
    assertEquals(value, 2);
  }

  @Test
  public void readsNothing() {}
}
