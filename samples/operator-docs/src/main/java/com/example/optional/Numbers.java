package com.example.optional;

import java.util.List;

public final class Numbers {
  /// The largest of `values`, or `ifNone` when there are none.
  public static int largest(List<Integer> values, int ifNone) {
    return values.stream().max(Integer::compare).orElse(ifNone);
  }
}
