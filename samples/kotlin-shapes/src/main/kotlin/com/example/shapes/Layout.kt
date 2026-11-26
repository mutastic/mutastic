package com.example.shapes

enum class Align { Start, Center, End }

class Style(val padding: Int?, val align: Align?)

class Cell(val text: String, val style: Style?)

/// Where each line of a cell starts, in a row `room` wide. `style?.align ?: Start` tests two values
/// and keeps each on the stack as it jumps: it's not an `&&` or an `||`.
class Placer(private val cell: Cell) {
    private val pad = cell.style?.padding ?: 0

    fun starts(room: Int): List<Int> {
        val align = cell.style?.align ?: Align.Start
        val starts = ArrayList<Int>()
        cell.text.split('\n').forEach { line ->
            val start = when (align) {
                Align.Start -> pad
                Align.Center -> (room - line.length) / 2 + pad
                Align.End -> room - line.length + pad
            }
            starts.add(start)
        }
        return starts
    }
}
