package com.example.shelf

/// Returns Kotlin has empty values for, some tested and some not.
class Shelf(private val size: Int) {
    fun slots(name: String): IntRange = if (name.isEmpty()) IntRange.EMPTY else 0 until size

    fun none(): IntRange = IntRange.EMPTY

    fun letters(name: String): CharRange = name.first()..name.last()

    fun positions(start: Long): LongRange = start until start + size

    fun labels(name: String): Sequence<String> = (0 until size).asSequence().map { "$name-$it" }

    fun noLabels(): Sequence<String> = emptySequence()

    fun names(name: String): List<String> = if (name.isEmpty()) emptyList() else listOf(name)

    fun noNames(): List<String> = emptyList()
}

data class Item(val name: String, val slots: IntRange)
