package com.example.shapes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

// Reaches every shape and looks at nothing: each card suggests the test that would.
class SuggestedTest {
    @Test
    fun callsRun() {
        plusOne(1)
        1.doubled()
        1.tripled
        1.plusBy(2)
        longer(1)
        letter(1)
        sign(1)
        sign(0)
        positive(4)
        half(2)
        orNull(1)
        orNull(0)
        refuses(0)
        withDefault(1)
        named(1)
        generic<String>(1)
        val counter = Counter(4)
        counter.next(1)
        counter.twice
        counter.getThrice()
        counter.Step(1).total()
        Counter.fromStatic(1)
        Counter.fromCompanion(1)
        Box<String>(1).next()
        Single.inc(1)
        viaHidden(1)
        viaLambda(listOf(1))
        isBlankName("a")
    }
}
