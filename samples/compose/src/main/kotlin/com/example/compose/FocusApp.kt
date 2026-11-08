package com.example.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.Button
import androidx.compose.material.Checkbox
import androidx.compose.material.LinearProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.material.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/// The whole app: the timer above today's tasks. While the timer runs, a second goes by each
/// second.
@Composable
fun FocusApp(timer: FocusTimer = remember { FocusTimer() }, tasks: TaskList = remember { TaskList() }) {
    LaunchedEffect(timer.running) {
        while (timer.running) {
            delay(1_000)
            timer.tick()
        }
    }
    MaterialTheme {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            TimerPanel(timer)
            TaskPanel(tasks, sessionsDone = timer.completedSessions)
        }
    }
}

@Composable
fun TimerPanel(timer: FocusTimer, modifier: Modifier = Modifier) {
    Column(modifier) {
        Text(timer.phase.title, style = MaterialTheme.typography.subtitle1)
        Text(timer.clock, Modifier.testTag("clock"), style = MaterialTheme.typography.h3)
        LinearProgressIndicator(timer.progress, Modifier.fillMaxWidth())
        Row {
            Button(onClick = { if (timer.running) timer.pause() else timer.start() }) {
                Text(if (timer.running) "Pause" else "Start")
            }
            Spacer(Modifier.width(8.dp))
            TextButton(onClick = timer::reset, enabled = !timer.running) { Text("Reset") }
        }
    }
}

/// Today's tasks, a field to add one, and how many sessions they'll take.
@Composable
fun TaskPanel(tasks: TaskList, sessionsDone: Int) {
    var draft by remember { mutableStateOf("") }
    var estimate by remember { mutableStateOf(1) }
    Column {
        Row {
            TextField(draft, { draft = it }, Modifier.testTag("draft"), label = { Text("Task") })
            TextButton(onClick = { estimate = estimate % TaskList.MAX_ESTIMATE + 1 }) {
                Text("${estimate}×")
            }
            Button(
                onClick = {
                    if (tasks.add(draft, estimate)) {
                        draft = ""
                        estimate = 1
                    }
                },
                enabled = draft.isNotBlank(),
            ) {
                Text("Add")
            }
        }
        for (task in tasks.tasks) {
            TaskRow(task, onToggle = { tasks.toggle(task.id) })
        }
        Summary(tasks.sessionsLeft(), sessionsDone)
    }
}

@Composable
fun TaskRow(task: Task, onToggle: () -> Unit) {
    Row {
        Checkbox(task.done, { onToggle() })
        Text(
            task.title,
            textDecoration = if (task.done) TextDecoration.LineThrough else TextDecoration.None,
        )
        Text("${task.estimate}×")
    }
}

/// What's left today, in words.
@Composable
fun Summary(sessionsLeft: Int, sessionsDone: Int) {
    val words =
        when {
            sessionsLeft == 0 && sessionsDone > 0 -> "All done: $sessionsDone sessions today"
            sessionsLeft == 0 -> "Nothing planned"
            sessionsLeft == 1 -> "1 session left"
            else -> "$sessionsLeft sessions left"
        }
    Text(words, Modifier.testTag("summary"))
}
