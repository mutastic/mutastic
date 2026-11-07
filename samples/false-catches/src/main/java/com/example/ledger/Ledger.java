package com.example.ledger;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Amounts kept in a file, one to a line.
public class Ledger {
  private final Path file;

  public Ledger(Path file) {
    this.file = file;
  }

  public void record(int amount) throws IOException {
    Files.writeString(file, amount + "\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
  }

  public int total() throws IOException {
    if (!Files.exists(file)) {
      return 0;
    }
    int total = 0;
    for (String line : Files.readAllLines(file)) {
      total += Integer.parseInt(line);
    }
    return total;
  }

  public boolean isEmpty() throws IOException {
    return total() == 0;
  }
}
