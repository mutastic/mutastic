package com.example.props

import io.kotest.property.Arb
import io.kotest.property.arbitrary.int
import io.kotest.property.forAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test

/** Kotest's property tests, from JUnit 5: a clamped number is between its bounds. */
class ClampTest {
    @Test
    fun staysBetweenTheBounds() = runBlocking<Unit> {
        forAll(Arb.int(-100..100)) { x -> Numbers.clamp(x, 0, 10) in 0..10 }
    }
}
