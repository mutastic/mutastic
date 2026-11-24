package com.example.shapes

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class LayoutTest {
    @Test
    fun startsAtThePadding() {
        assertEquals(listOf(1), Placer(Cell("ab", Style(1, null))).starts(10))
    }

    @Test
    fun centresEachLine() {
        assertEquals(listOf(4, 3), Placer(Cell("ab\ncde", Style(0, Align.Center))).starts(10))
    }

    @Test
    fun endsAtTheRoom() {
        assertEquals(listOf(8), Placer(Cell("ab", Style(null, Align.End))).starts(10))
    }
}
