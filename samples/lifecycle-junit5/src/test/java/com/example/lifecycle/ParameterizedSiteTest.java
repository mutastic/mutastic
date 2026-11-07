package com.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ParameterizedSiteTest {
  @ParameterizedTest
  @ValueSource(ints = {1, 1})
  void runsInEachCase(int x) {
    assertEquals(2, Sites.parameterizedValue(x));
    Sites.parameterizedThrows(x);
  }

  @Test
  void readsNothing() {}
}
