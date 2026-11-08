package com.example.setup;

import static org.junit.Assert.assertEquals;

import org.junit.BeforeClass;
import org.junit.Test;

public class VintageSetupTest {
  // Fails the first time it runs in a JVM, then every third time, like a setup racing a server.
  @BeforeClass
  public static void connect() {
    int runs = Integer.getInteger("setup.vintage", 0) + 1;
    System.setProperty("setup.vintage", String.valueOf(runs));
    if (runs % 3 == 1) {
      throw new IllegalStateException("the server wasn't up yet");
    }
  }

  @Test
  public void thirds() {
    Prices.thirded(9);
  }

  @Test
  public void doubles() {
    assertEquals(6, Prices.doubled(3));
  }
}
