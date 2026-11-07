package com.example.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.io.TempDir;

@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LedgerTest {
  // Shared on purpose: one file in the build directory, which nothing deletes. The first two tests
  // pass the first time the suite runs; after that, the entry the second leaves behind fails both.
  // So mutastic runs on a fresh copy of this sample: on this one again, it stops before any mutant.
  private static final Path SHARED = Path.of("build", "ledger.txt");

  @Test
  @Order(1)
  void aNewLedgerIsEmpty() throws IOException {
    assertTrue(new Ledger(SHARED).isEmpty());
  }

  @Test
  @Order(2)
  void recordingAddsToTheTotal() throws IOException {
    var ledger = new Ledger(SHARED);
    ledger.record(5);
    assertEquals(5, ledger.total());
  }

  @Test
  @Order(3)
  void aLedgerOfItsOwnAddsUp(@TempDir Path directory) throws IOException {
    var ledger = new Ledger(directory.resolve("ledger.txt"));
    ledger.record(5);
    ledger.record(-7);
    assertEquals(-2, ledger.total());
  }
}
