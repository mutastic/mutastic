package com.example.lifecycle;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.stream.IntStream;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class MethodSourceSiteTest {
  static IntStream values() {
    Sites.methodSourceThrows(1);
    return IntStream.of(Sites.methodSourceValue(1));
  }

  @ParameterizedTest
  @MethodSource("values")
  void readsWhatTheFactoryMade(int value) {
    assertEquals(2, value);
  }

  @Test
  void readsNothing() {}
}
