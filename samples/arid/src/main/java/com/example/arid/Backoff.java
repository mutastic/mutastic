package com.example.arid;

import java.util.function.BooleanSupplier;

/** Asks until something is ready, pausing between tries so as not to flood what it asks. */
public final class Backoff {
  private Backoff() {}

  public static int poll(BooleanSupplier ready, long pauseMillis) throws InterruptedException {
    int tries = 1;
    while (!ready.getAsBoolean()) {
      Thread.sleep(pauseMillis);
      tries++;
    }
    return tries;
  }
}
