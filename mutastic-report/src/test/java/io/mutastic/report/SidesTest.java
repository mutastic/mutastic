package io.mutastic.report;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SidesTest {
  @Test
  void readsEachSideOutToWhatEndsTheCondition() {
    assertSides("if (a > 0 && b < 2) {", "&&", "a > 0", "b < 2");
    assertSides("return !(even(a) || small(b));", "||", "even(a)", "small(b)");
    assertSides("boolean both = x == y || limit(z, 2) >= 1;", "||", "x == y", "limit(z, 2) >= 1");
    assertSides(
        ".filter(item -> item.ok() && seen.add(item))", "&&", "item.ok()", "seen.add(item)");
    assertSides("return a >= -1 && b <= 2 ? 1 : 2;", "&&", "a >= -1", "b <= 2");
  }

  @Test
  void aSideWithAStringIsNotRead() {
    var line = "if (name.equals(\"x\") && ready) {";
    assertThat(Sides.left(line, line.indexOf("&&"))).isEmpty();
    assertThat(Sides.right(line, line.indexOf("&&") + 2)).contains("ready");
  }

  private static void assertSides(String line, String connector, String left, String right) {
    int at = line.indexOf(connector);
    assertThat(Sides.left(line, at)).contains(left);
    assertThat(Sides.right(line, at + connector.length())).contains(right);
  }
}
