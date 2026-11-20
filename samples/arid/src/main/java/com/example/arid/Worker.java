package com.example.arid;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** Work done on another thread, waited for up to a time limit. */
public final class Worker {
  private Worker() {}

  public static long result(LongSupplier work, int seconds) throws InterruptedException {
    var result = new AtomicLong();
    var worker = new Thread(() -> result.set(work.getAsLong()));
    worker.start();
    worker.join(seconds * 1000L);
    return result.get();
  }
}
