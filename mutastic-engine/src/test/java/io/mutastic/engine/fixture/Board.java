package io.mutastic.engine.fixture;

import java.util.List;
import java.util.stream.Collectors;

/// A debug representation and the code that only serves it: arid, all of it. Code anyone else may
/// call is real code, even when `toString()` calls it too.
public class Board {
  private final List<Integer> cells = List.of(1, 2, 3);
  private final int size = 3;

  @Override
  public String toString() {
    return cells.stream().map(cell -> cell(cell, 2)).collect(Collectors.joining("|")) + rows();
  }

  /// Only `toString()` calls it, and itself: arid.
  private String cell(int value, int depth) {
    return depth > 0 ? cell(value, depth - 1) : value > 1 ? "x" : "o";
  }

  /// Public: anyone may call it, so it's real code.
  public int rows() {
    return size > 2 ? size : 0;
  }

  @Override
  public int hashCode() {
    return size * 31 + cells.hashCode();
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Board board && board.size == size;
  }
}
