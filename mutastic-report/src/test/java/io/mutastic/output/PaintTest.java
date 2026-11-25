package io.mutastic.output;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class PaintTest {
  @Test
  void aRoleIsItsCodeThenAReset() {
    assertThat(Paint.paint(true, Paint.RED, Glyphs.FAIL)).isEqualTo("\u001b[1;31m✗\u001b[0m");
  }

  @Test
  void withoutColourTheTextIsAsItIs() {
    assertThat(Paint.paint(false, Paint.GREEN, Glyphs.PASS)).isEqualTo("✓");
  }
}
