package com.example.tally

import kotlin.test.Test
import kotlin.test.assertEquals

class TallyTest {
    @Test
    fun aHighScoreGetsTheBonus() {
        assertEquals(130, points(120))
    }

    @Test
    fun aLowScoreDoesnt() {
        assertEquals(50, points(50))
    }
}
