package com.example.protocol;

/// What a server and its clients agree on.
public final class Protocol {
  /// The message that ends a game: javac writes `"{}"` in wherever this is read.
  public static final String EXIT_CODE = "{}";

  private Protocol() {}
}
