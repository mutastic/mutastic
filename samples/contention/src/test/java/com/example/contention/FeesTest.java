package com.example.contention;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;

class FeesTest {
  // Holds a lock on a shared file for 3 s, as a test of a shared database or a fixed directory
  // does. Copies running side by side wait for each other, and the later ones run out of time. The
  // file is in the project, not the temporary directory: each worker has a temporary directory of
  // its own.
  @Test
  void chargesPerTenStarted() throws Exception {
    var lock = Path.of("build", "mutastic-contention.lock");
    try (var channel = FileChannel.open(lock, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var held = channel.lock()) {
      Thread.sleep(3000);
      assertEquals(0, new Fees().fee(5));
      assertEquals(3, new Fees().fee(25));
      // Nothing checks 10 itself, so `<` becoming `<=` goes unnoticed.
    }
  }
}
