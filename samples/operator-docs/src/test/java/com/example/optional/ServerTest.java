package com.example.optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import org.junit.jupiter.api.Test;

class ServerTest {
  @Test
  void theDefaultPort() {
    assertEquals(8080, new Server(8080).port(Optional.empty()));
  }

  @Test
  void theConfiguredPort() {
    assertEquals(8080, new Server(8080).port(Optional.of(8080)));
  }
}
