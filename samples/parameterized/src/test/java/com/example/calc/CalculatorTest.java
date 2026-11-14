package com.example.calc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/// Only parameterized tests, so `Test` isn't imported, and each source is weak somewhere.
class CalculatorTest {
  private final Calculator calculator = new Calculator();

  @ParameterizedTest(name = "{0} + {1} = {2} \"quoted\", #hash [x] (y) é ✓")
  @CsvSource({"100, 0, 100"})
  void adds(int a, int b, int expected) {
    assertEquals(expected, calculator.add(a, b));
  }

  @ParameterizedTest
  @CsvSource({"1, 1, 1", "2, 1, 2"})
  void multiplies(int a, int b, int expected) {
    assertEquals(expected, calculator.multiply(a, b));
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 4})
  void evens(int n) {
    assertTrue(calculator.isEven(n));
  }

  static Stream<Arguments> totals() {
    return Stream.of(Arguments.of(50, 0), Arguments.of(250, 25));
  }

  @ParameterizedTest
  @MethodSource("totals")
  void discounts(int total, int expected) {
    assertEquals(expected, calculator.discount(total));
  }

  // Only the second row catches a + b becoming a - b.
  @ParameterizedTest
  @CsvSource({"0, 0, 0", "2, 3, 5"})
  void addsMixed(int a, int b, int expected) {
    assertEquals(expected, calculator.add(a, b));
  }
}
