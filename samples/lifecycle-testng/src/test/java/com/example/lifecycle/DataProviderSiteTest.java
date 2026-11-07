package com.example.lifecycle;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.*;

public class DataProviderSiteTest {
  @DataProvider
  public Object[][] values() {
    Sites.dataProviderThrows(1);
    return new Object[][] {{Sites.dataProviderValue(1)}};
  }

  @Test(dataProvider = "values")
  public void readsWhatTheProviderMade(int value) {
    assertEquals(value, 2);
  }

  @Test
  public void readsNothing() {}
}
