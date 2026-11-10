package com.example.tally

/// What the desktop app shows for a round.
fun label(score: Int): String = if (points(score) > 0) "won ${points(score)}" else "lost"
