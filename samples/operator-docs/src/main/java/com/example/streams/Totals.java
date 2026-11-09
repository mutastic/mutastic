package com.example.streams;

import java.util.List;

public final class Totals {
  /// The total, with each value written to `log` as it's added.
  public static int logged(List<Integer> values, List<Integer> log) {
    return values.stream().peek(log::add).mapToInt(Integer::intValue).sum();
  }
}
