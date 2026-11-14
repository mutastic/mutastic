package com.example.calc

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.Arguments
import org.junit.jupiter.params.provider.CsvSource
import org.junit.jupiter.params.provider.MethodSource
import org.junit.jupiter.params.provider.ValueSource
import java.util.stream.Stream

/// Only parameterized tests, so `Test` isn't imported, and each row is weak somewhere.
class CalculatorTest {
    private val calculator = Calculator()

    @ParameterizedTest
    @CsvSource("100, 0, 100")
    fun adds(a: Int, b: Int, expected: Int) {
        assertEquals(expected, calculator.add(a, b))
    }

    @ParameterizedTest(name = "{0} - {1} = {2} \"quoted\" #hash [x] (y) é ✓")
    @CsvSource("30, 0, 30")
    fun `subtracts with spaces`(a: Int, b: Int, expected: Int) {
        assertEquals(expected, calculator.subtract(a, b))
    }

    @ParameterizedTest
    @CsvSource("1, 1, 1", "2, 1, 2")
    fun multiplies(a: Int, b: Int, expected: Int) {
        assertEquals(expected, calculator.multiply(a, b))
    }

    @ParameterizedTest
    @ValueSource(ints = [2, 4])
    fun evens(n: Int) {
        assertTrue(calculator.isEven(n))
    }

    @ParameterizedTest
    @MethodSource("totals")
    fun discounts(total: Int, expected: Int) {
        assertEquals(expected, calculator.discount(total))
    }

    companion object {
        @JvmStatic
        fun totals(): Stream<Arguments> = Stream.of(Arguments.of(50, 0), Arguments.of(250, 25))
    }
}
