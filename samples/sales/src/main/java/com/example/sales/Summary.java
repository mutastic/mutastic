package com.example.sales;

import java.io.PrintStream;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A day's sales, fetched on a worker thread, as text: each amount on a line, then the total. */
public final class Summary {
  private static final Logger log = LoggerFactory.getLogger(Summary.class);

  private Summary() {}

  /** What the report command prints. */
  public static void print(PrintStream out, Supplier<List<Long>> sales)
      throws InterruptedException {
    out.println(of(sales));
  }

  public static String of(Supplier<List<Long>> sales) throws InterruptedException {
    long start = System.nanoTime();
    var fetched = new AtomicReference<List<Long>>(List.of());
    var worker = new Thread(() -> fetched.set(sales.get()));
    worker.start();
    worker.join(5_000);
    List<Long> amounts = fetched.get();
    var text = new StringBuilder(amounts.size() * 8);
    long total = 0;
    for (long amount : amounts) {
      text.append(amount).append('\n');
      total += amount;
    }
    text.append("total ").append(total);
    log.info(
        "summed {} amounts in {} ms", amounts.size(), (System.nanoTime() - start) / 1_000_000);
    return text.toString();
  }
}
