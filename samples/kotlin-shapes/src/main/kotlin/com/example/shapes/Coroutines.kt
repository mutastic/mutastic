package com.example.shapes

import kotlinx.coroutines.delay

suspend fun nextOf(n: Int): Int {
    delay(1)
    return n + 1
}

suspend fun waits(n: Int) = delay(n.toLong())

suspend fun sumTo(n: Int): Int {
    var total = 0
    for (i in 0 until n) {
        total += nextOf(i)
    }
    return total
}

fun doubles(n: Int): Sequence<Int> = sequence {
    for (i in 0 until n) {
        yield(i * 2)
    }
}
