package com.example.setup;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;

class JupiterTeardownTest {
  // Fails the first time it runs in a JVM, then every third time, after its test has passed: the
  // test ran, so it reached the code, but a failure here would look like a catch.
  @AfterAll
  static void disconnect() {
    int runs = Integer.getInteger("teardown.jupiter", 0) + 1;
    System.setProperty("teardown.jupiter", String.valueOf(runs));
    if (runs % 3 == 1) {
      throw new IllegalStateException("the server went away first");
    }
  }

  @Test
  void fifths() {
    Prices.fifthed(10);
  }
}
