package com.example.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class ConfigurationTest {
  @Test
  void readsThePercent() {
    Settings.percent = Integer.parseInt("10");
    assertEquals(10, Settings.percent);
  }
}
