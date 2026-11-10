package com.example.app;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class SettingsTest {
  /// Without `app.port` the app doesn't start: its settings can't be read.
  @Test
  void aPortMustBeSet() {
    assertThrows(ExceptionInInitializerError.class, Settings::privileged);
  }
}
