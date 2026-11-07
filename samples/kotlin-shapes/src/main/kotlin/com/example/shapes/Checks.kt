package com.example.shapes

class Late {
    lateinit var name: String
    private lateinit var inner: String

    fun put(value: String) {
        inner = value
    }

    fun nameLength(): Int = name.length

    fun innerLength(): Int = inner.length
}

fun keep(text: String?): String = text!!

fun lengthOf(text: String?): Int = text!!.length

class Holder(@JvmField val size: Int)

fun sizeOf(holder: Holder?): Int = holder!!.size

fun countOf(numbers: IntArray?): Int = numbers!!.size

fun asText(value: Any?): String = value as String
