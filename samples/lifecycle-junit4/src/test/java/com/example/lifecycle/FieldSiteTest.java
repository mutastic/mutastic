package com.example.lifecycle;

import static org.junit.Assert.assertEquals;

import org.junit.*;

public class FieldSiteTest {
  final int value = Sites.fieldValue(1);
  final int thrown = Sites.fieldThrows(1);

  @Test
  public void readsWhatSetUpMade() {
    assertEquals(2, value);
  }

  @Test
  public void readsNothing() {}
}
