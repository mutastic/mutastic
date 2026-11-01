package com.example.ports;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.ServerSocket;
import org.junit.jupiter.api.Test;

class FeesTest {
  // Binds a fixed port while it runs, as a test with a hard-coded server port does. Two copies
  // running at once can't both bind it.
  @Test
  void chargesPerTenStarted() throws Exception {
    try (var server = new ServerSocket(47613)) {
      Thread.sleep(150);
      assertEquals(0, new Fees().fee(5));
      assertEquals(3, new Fees().fee(25));
      // Nothing checks 10 itself, so `<` becoming `<=` goes unnoticed.
    }
  }
}
