package com.example.compose

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import org.junit.jupiter.api.Test

/// The app driven through its UI, on a virtual clock.
@OptIn(ExperimentalTestApi::class)
class FocusAppTest {
    @Test
    fun startingTheTimerCountsDownEachSecond() = runComposeUiTest {
        mainClock.autoAdvance = false
        setContent { FocusApp(FocusTimer(focusSeconds = 60)) }

        onNodeWithText("Start").performClick()
        mainClock.advanceTimeBy(3_500)

        onNodeWithTag("clock").assertTextEquals("00:57")
        onNodeWithText("Pause").assertExists()
    }

    @Test
    fun anAddedTaskIsListed() = runComposeUiTest {
        setContent { FocusApp() }

        onNodeWithTag("draft").performTextInput("Plan the week")
        onNodeWithText("Add").performClick()

        onNodeWithText("Plan the week").assertExists()
        onNodeWithTag("summary").assertTextEquals("1 session left")
    }
}
