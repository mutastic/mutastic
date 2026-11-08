package com.example.setup;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class JupiterSetupTest {
  // Fails the first time it runs in a JVM, then every third time, like a setup racing a server.
  @BeforeAll
  static void connect() {
    int runs = Integer.getInteger("setup.jupiter", 0) + 1;
    System.setProperty("setup.jupiter", String.valueOf(runs));
    if (runs % 3 == 1) {
      throw new IllegalStateException("the server wasn't up yet");
    }
  }

  @Test
  void halves() {
    Prices.halved(4);
  }

  @Test
  void doubles() {
    assertEquals(6, Prices.doubled(3));
  }
}
