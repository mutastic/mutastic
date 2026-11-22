package com.example.arid;

import java.util.List;
import java.util.function.Consumer;

/** Sends messages one at a time, pausing after each so a slow reader keeps up. */
public final class Pacer {
  private Pacer() {}

  public static int send(List<String> messages, Consumer<String> reader, int pauseTenths)
      throws InterruptedException {
    for (var message : messages) {
      reader.accept(message);
      Thread.sleep(pauseTenths * 100L);
    }
    return messages.size();
  }
}
