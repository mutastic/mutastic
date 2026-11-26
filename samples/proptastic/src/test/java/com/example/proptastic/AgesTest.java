package com.example.proptastic;

import static io.proptastic.Proptastic.forAll;
import static io.proptastic.Proptastic.integers;
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class AgesTest {
  @Test
  void adultsAreEighteenOrOver() {
    forAll(integers().atLeast(0).atMost(120))
        .check(age -> assertEquals(age >= 18, Ages.isAdult(age)));
  }
}
