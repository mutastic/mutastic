package io.mutastic.output;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class BarTest {
  @Test
  void aBarFillsToItsFraction() {
    assertThat(Bar.of(0.5, 4, false)).isEqualTo("━━──");
    assertThat(Bar.of(2.0, 2, false)).isEqualTo("━━");
    assertThat(Bar.of(0.0, 2, true)).isEqualTo("\u001b[32m\u001b[0m\u001b[2m──\u001b[0m");
  }
}
