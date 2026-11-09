package com.example.optional;

import java.util.Optional;

public final class Server {
  private final int defaultPort;

  public Server(int defaultPort) {
    this.defaultPort = defaultPort;
  }

  public int port(Optional<Integer> configured) {
    return configured.orElse(defaultPort);
  }
}
