package com.example.rates;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class WindowTest {
  @Test
  void openInTheMiddle() {
    assertThat(new Window(1, 10L).openOn(5)).isTrue(); // nothing checks the first or last day
  }
}
