package com.example.shapes

import java.io.StringReader

// `use` closes what it's given, as a try-with-resources does: kotlinc copies its body in from the
// standard library, and that copy is the library's code, not this file's.
fun firstChar(text: String): Int = StringReader(text).use { it.read() }

// The same for an AutoCloseable that isn't a Closeable, whose `use` is another function.
fun released(log: StringBuilder): Int =
    AutoCloseable { log.append("closed") }.use { log.length }
