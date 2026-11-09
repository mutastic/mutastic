package com.example.protocol;

import java.util.Queue;

public class Messages {
  /// How many messages come before the one that ends the game.
  public int beforeExit(Queue<String> messages) {
    int count = 0;
    String message;
    while ((message = messages.poll()) != null && !message.equals(Protocol.EXIT_CODE)) {
      count++;
    }
    return count;
  }

  /// Whether a setting of this type holds a number.
  public boolean numeric(Class<?> type) {
    if (Number.class.isAssignableFrom(type)) {
      return true;
    }
    return type == int.class || type == long.class;
  }
}
