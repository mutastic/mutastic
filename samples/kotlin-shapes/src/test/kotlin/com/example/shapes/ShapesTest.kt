package com.example.shapes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShapesTest {
    @Test
    fun inlineCallsRun() {
        usesTwice(1)
        usesBump(1)
        calledFromATest { 5 }
        assertEquals(1, evens(listOf(1, 2)).size)
    }
}
