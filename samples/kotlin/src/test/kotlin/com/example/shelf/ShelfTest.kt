package com.example.shelf

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ShelfTest {
    private val shelf = Shelf(3)

    @Test
    fun slotsCountUp() {
        assertEquals(listOf(0, 1, 2), shelf.slots("a").toList())
    }

    @Test
    fun noneIsEmpty() {
        assertEquals(emptyList<Int>(), shelf.none().toList())
    }

    @Test
    fun labelsAreCalled() {
        shelf.labels("a")
    }

    @Test
    fun noLabelsIsEmpty() {
        assertEquals(0, shelf.noLabels().count())
    }

    @Test
    fun lettersAndPositionsRun() {
        shelf.letters("ab")
        shelf.positions(1)
    }

    @Test
    fun namesAreFound() {
        assertEquals(listOf("a"), shelf.names("a"))
        assertEquals(emptyList<String>(), shelf.noNames())
    }

    @Test
    fun anItemKeepsItsName() {
        assertEquals("a", Item("a", 0..1).name)
    }
}
