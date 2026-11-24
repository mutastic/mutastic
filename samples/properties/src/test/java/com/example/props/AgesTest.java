package com.example.props;

import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.SourceDSL.integers;

import org.junit.jupiter.api.Test;

/** QuickTheories, on JUnit 5: few examples over many ages, so whether one is 18 is the draw's. */
class AgesTest {
  @Test
  void adultsAre18OrOlder() {
    qt().withExamples(12)
        .forAll(integers().between(0, 59))
        .check(age -> Ages.isAdult(age) == age > 17);
  }
}
