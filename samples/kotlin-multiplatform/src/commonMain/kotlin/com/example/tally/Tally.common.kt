package com.example.tally

/// A round's points: a bonus from 100 up.
fun points(score: Int): Int = if (score >= 100) score + 10 else score
