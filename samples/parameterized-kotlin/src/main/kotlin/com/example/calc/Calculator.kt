package com.example.calc

class Calculator {
    fun add(a: Int, b: Int): Int = a + b

    fun subtract(a: Int, b: Int): Int = a - b

    fun multiply(a: Int, b: Int): Int = a * b

    fun discount(total: Int): Int {
        if (total >= 100) {
            return total / 10
        }
        return 0
    }

    fun isEven(n: Int): Boolean = n % 2 == 0
}
