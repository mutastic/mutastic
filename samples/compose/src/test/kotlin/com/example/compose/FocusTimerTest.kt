package com.example.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class FocusTimerTest {
    @Test
    fun aRunningTimerCountsDown() {
        val timer = FocusTimer(focusSeconds = 90)
        timer.start()

        timer.tick()

        assertEquals(89, timer.remaining)
        assertEquals("01:29", timer.clock)
    }

    @Test
    fun aPausedTimerStaysPut() {
        val timer = FocusTimer(focusSeconds = 90)

        timer.tick()

        assertEquals(90, timer.remaining)
    }

    @Test
    fun aFinishedFocusSessionStartsABreak() {
        val timer = FocusTimer(focusSeconds = 2, breakSeconds = 5)
        timer.start()

        repeat(2) { timer.tick() }

        assertEquals(Phase.Break, timer.phase)
        assertEquals(1, timer.completedSessions)
        assertFalse(timer.running)
    }

    // Never reaches a fourth session, so the long break goes untested.
}
