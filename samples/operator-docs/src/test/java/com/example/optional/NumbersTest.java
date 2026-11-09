package com.example.optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class NumbersTest {
  @Test
  void theLargest() {
    assertEquals(9, Numbers.largest(List.of(3, 9), 0));
  }
}
