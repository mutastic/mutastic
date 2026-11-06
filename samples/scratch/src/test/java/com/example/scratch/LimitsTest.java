package com.example.scratch;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LimitsTest {
  // Each temporary directory the test makes is listed in build/scratch.txt, and each process it
  // starts in build/children.txt, so the run's tests can check none is left behind. A mutant that
  // makes 3 not fit in 4 keeps the test waiting forever: its worker is killed mid-test, before the
  // test framework can delete the directory. Nothing here stops the process, as a test that forgets
  // to doesn't, but it ends by itself in time, so a failing run leaves no strays for long.
  @Test
  void fitsUpToItsCapacity(@TempDir Path scratch) throws Exception {
    Files.writeString(
        Path.of("build", "scratch.txt"),
        scratch + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
    Files.writeString(scratch.resolve("buffer.txt"), "abc");
    var child = new ProcessBuilder("sleep", "300").start();
    Files.writeString(
        Path.of("build", "children.txt"),
        child.pid() + System.lineSeparator(),
        StandardOpenOption.CREATE,
        StandardOpenOption.APPEND);
    while (!new Limits().fits(3, 4)) {
      Thread.onSpinWait();
    }
    assertTrue(new Limits().fits(4, 4));
    assertFalse(new Limits().fits(5, 4));
  }
}
