package com.example.arid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class TotalsTest {
  @Test
  void printsTheTotal() {
    var printed = new ByteArrayOutputStream();
    Totals.print(new PrintStream(printed, true, StandardCharsets.UTF_8), 42);
    assertEquals("total: 42" + System.lineSeparator(), printed.toString(StandardCharsets.UTF_8));
  }
}
