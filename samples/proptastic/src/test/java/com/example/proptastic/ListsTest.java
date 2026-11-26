package com.example.proptastic;

import static io.proptastic.Proptastic.forAll;
import static io.proptastic.Proptastic.integers;
import static io.proptastic.Proptastic.lists;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;

class ListsTest {
  @Test
  void reversesTwice() {
    forAll(lists(integers())).check(xs -> assertEquals(xs, Lists.reversed(Lists.reversed(xs))));
  }

  @Test
  void sortsAsTheJdkDoes() {
    forAll(lists(integers()))
        .check(
            xs -> {
              var expected = new ArrayList<>(xs);
              Collections.sort(expected);
              assertEquals(expected, Lists.sorted(xs));
            });
  }
}
