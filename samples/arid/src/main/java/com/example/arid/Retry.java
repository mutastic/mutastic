package com.example.arid;

import java.util.function.BooleanSupplier;

/** A call tried up to three times. */
public final class Retry {
  private Retry() {}

  public static int attempts(BooleanSupplier call) {
    int attempt = 0;
    while (attempt < 3) {
      attempt++;
      if (call.getAsBoolean()) {
        return attempt;
      }
    }
    return attempt;
  }
}
