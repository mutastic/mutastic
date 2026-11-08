package com.example.compose

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Phase(val title: String) {
    Focus("Focus"),
    Break("Short break"),
    LongBreak("Long break"),
}

/// A focus timer: work for a while, then a break, and a longer break after every fourth session.
/// Its state is Compose state, so the screen recomposes when it changes.
class FocusTimer(
    private val focusSeconds: Int = 25 * 60,
    private val breakSeconds: Int = 5 * 60,
    private val longBreakSeconds: Int = 15 * 60,
) {
    var phase by mutableStateOf(Phase.Focus)
        private set

    var remaining by mutableStateOf(focusSeconds)
        private set

    var running by mutableStateOf(false)
        private set

    var completedSessions by mutableStateOf(0)
        private set

    fun start() {
        if (remaining > 0) {
            running = true
        }
    }

    fun pause() {
        running = false
    }

    /// One second gone, if the timer is running.
    fun tick() {
        if (!running) {
            return
        }
        remaining -= 1
        if (remaining <= 0) {
            finishPhase()
        }
    }

    fun reset() {
        running = false
        phase = Phase.Focus
        remaining = focusSeconds
    }

    private fun finishPhase() {
        running = false
        if (phase == Phase.Focus) {
            completedSessions += 1
            phase = if (completedSessions % 4 == 0) Phase.LongBreak else Phase.Break
        } else {
            phase = Phase.Focus
        }
        remaining = lengthOf(phase)
    }

    private fun lengthOf(phase: Phase): Int =
        when (phase) {
            Phase.Focus -> focusSeconds
            Phase.Break -> breakSeconds
            Phase.LongBreak -> longBreakSeconds
        }

    /// What the clock shows: minutes and seconds.
    val clock: String
        get() = "%02d:%02d".format(remaining / 60, remaining % 60)

    /// How much of this phase has gone, from 0 to 1.
    val progress: Float
        get() = 1f - remaining.toFloat() / lengthOf(phase)
}
