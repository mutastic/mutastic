package com.example.compose

import androidx.compose.runtime.mutableStateListOf

data class Task(val id: Int, val title: String, val estimate: Int, val done: Boolean = false)

/// The tasks for today, each with an estimate in focus sessions.
class TaskList {
    private val items = mutableStateListOf<Task>()
    private var nextId = 1

    val tasks: List<Task>
        get() = items

    /// Adds a task, unless its title is blank, it's already on the list, or its estimate is out of
    /// range. Says whether it was added.
    fun add(title: String, estimate: Int = 1): Boolean {
        val trimmed = title.trim()
        if (trimmed.isEmpty() || estimate < 1 || estimate > MAX_ESTIMATE) {
            return false
        }
        if (items.any { it.title.equals(trimmed, ignoreCase = true) && !it.done }) {
            return false
        }
        items += Task(nextId++, trimmed, estimate)
        return true
    }

    fun toggle(id: Int) {
        val at = items.indexOfFirst { it.id == id }
        if (at >= 0) {
            items[at] = items[at].copy(done = !items[at].done)
        }
    }

    /// The focus sessions the tasks still to do will take.
    fun sessionsLeft(): Int = items.filterNot { it.done }.sumOf { it.estimate }

    fun clearDone() {
        items.removeAll { it.done }
    }

    companion object {
        const val MAX_ESTIMATE = 8
    }
}
