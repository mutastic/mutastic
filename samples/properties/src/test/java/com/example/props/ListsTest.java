package com.example.props;

import static org.quicktheories.QuickTheory.qt;
import static org.quicktheories.generators.SourceDSL.integers;
import static org.quicktheories.generators.SourceDSL.lists;

import org.junit.jupiter.api.Test;

/** QuickTheories, on JUnit 5: any list, reversed twice, is itself. */
class ListsTest {
  @Test
  void reversesTwice() {
    qt().forAll(lists().of(integers().between(-1000, 1000)).ofSizeBetween(0, 40))
        .check(xs -> Lists.reversed(Lists.reversed(xs)).equals(xs));
  }
}
