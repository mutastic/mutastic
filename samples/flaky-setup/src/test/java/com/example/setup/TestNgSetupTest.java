package com.example.setup;

import static org.testng.Assert.assertEquals;

import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

public class TestNgSetupTest {
  // Fails the first time it runs in a JVM, then every third time, like a setup racing a server.
  @BeforeClass
  public void connect() {
    int runs = Integer.getInteger("setup.testng", 0) + 1;
    System.setProperty("setup.testng", String.valueOf(runs));
    if (runs % 3 == 1) {
      throw new IllegalStateException("the server wasn't up yet");
    }
  }

  @Test
  public void quarters() {
    Prices.quartered(8);
  }

  @Test
  public void doubles() {
    assertEquals(Prices.doubled(3), 6);
  }
}
