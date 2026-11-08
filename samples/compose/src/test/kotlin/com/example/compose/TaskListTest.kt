package com.example.compose

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TaskListTest {
    @Test
    fun aTaskIsAddedTrimmed() {
        val tasks = TaskList()

        assertTrue(tasks.add("  Write the report ", 2))

        assertEquals("Write the report", tasks.tasks.single().title)
        assertEquals(2, tasks.sessionsLeft())
    }

    @Test
    fun aBlankTaskIsRefused() {
        val tasks = TaskList()

        assertFalse(tasks.add("   "))
    }

    @Test
    fun aDoneTaskTakesNoMoreSessions() {
        val tasks = TaskList()
        tasks.add("Read", 3)
        tasks.add("Write", 2)

        tasks.toggle(tasks.tasks.first().id)

        assertEquals(2, tasks.sessionsLeft())
    }
}
