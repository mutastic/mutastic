package com.example.shapes

import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

class ShapesTest {
    @Test
    fun inlineCallsRun() {
        usesTwice(1)
        usesBump(1)
        calledFromATest { 5 }
        assertEquals(1, evens(listOf(1, 2)).size)
    }

    @Test
    fun coroutinesRun() {
        runBlocking {
            nextOf(1)
            waits(1)
            sumTo(2)
        }
        doubles(2).toList()
    }

    @Test
    fun checksRun() {
        val late = Late()
        late.name = "a"
        late.nameLength()
        late.put("b")
        late.innerLength()
        keep("a")
        lengthOf("a")
        asText("a")
        sizeOf(Holder(1))
        countOf(intArrayOf(1))
        assertThrows(NullPointerException::class.java) { lengthOf(null) }
    }
}
