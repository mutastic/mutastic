package com.example.shapes

fun plusOne(n: Int): Int = n + 1

fun Int.doubled(): Int = this * 2

val Int.tripled: Int
    get() = this * 3

fun Int.plusBy(other: Int): Int = this + other

fun longer(n: Long): Long = n + 1

fun letter(n: Int): Char = (n + 65).toChar()

fun sign(n: Int): String = if (n > 0) "\$up" else "down"

fun positive(n: Int): Boolean = n > 3

fun half(n: Int): Double = n * 1.5

fun orNull(n: Int): Int? = if (n > 0) n else null

fun refuses(n: Int): Int {
    if (n > 0) {
        throw IllegalArgumentException("no")
    }
    return n
}

fun withDefault(n: Int, step: Int = 2): Int = n + step

@JvmName("renamed")
fun named(n: Int): Int = n + 1

fun <T> generic(n: Int): Int = n + 1

class Counter(private val start: Int) {
    fun next(by: Int): Int = start + by

    val twice: Int
        get() = start * 2

    fun getThrice(): Int = start * 3

    inner class Step(private val size: Int) {
        fun total(): Int = start + size
    }

    companion object {
        @JvmStatic
        fun fromStatic(n: Int): Int = n + 1

        fun fromCompanion(n: Int): Int = n - 1
    }
}

class Box<T>(private val n: Int) {
    fun next(): Int = n + 1
}

object Single {
    fun inc(n: Int): Int = n + 1
}

private class Hidden(private val n: Int) {
    fun twice(): Int = n * 2
}

fun viaHidden(n: Int): Int = Hidden(n).twice()

// A sequence's map isn't inline, so its block is a lambda of its own.
fun viaLambda(numbers: List<Int>): List<Int> = numbers.asSequence().map { it + 1 }.toList()

fun isBlankName(name: String): Boolean = name.isEmpty()
