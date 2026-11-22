package com.example.sales;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

class SummaryTest {
  @Test
  void printsEachAmountThenTheTotal() throws Exception {
    var printed = new ByteArrayOutputStream();
    Summary.print(
        new PrintStream(printed, true, StandardCharsets.UTF_8),
        slowly(List.of(12L, 30L)),
        (name, value) -> {});
    assertEquals(
        "12\n30\ntotal 42" + System.lineSeparator(), printed.toString(StandardCharsets.UTF_8));
  }

  /** Sales as a real source gives them: after a moment. */
  private static Supplier<List<Long>> slowly(List<Long> sales) {
    return () -> {
      try {
        Thread.sleep(100);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      return sales;
    };
  }
}
