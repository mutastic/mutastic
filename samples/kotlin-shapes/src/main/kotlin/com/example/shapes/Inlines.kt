package com.example.shapes

inline fun twice(f: () -> Int): Int = f() + f()

fun usesTwice(x: Int): Int = twice { x + 1 }

fun usesBump(x: Int): Int = bump(x)

inline fun neverCalled(f: () -> Int): Int = f() * 2

inline fun calledFromATest(f: () -> Int): Int = f() * 4

inline fun calledFromJava(f: () -> Int): Int = f() * 3

fun alsoCopied(): Int = calledFromJava { 2 }

fun evens(numbers: List<Int>): List<Int> = numbers.filter { it % 2 == 0 }
