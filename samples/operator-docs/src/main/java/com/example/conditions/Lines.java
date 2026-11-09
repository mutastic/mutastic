package com.example.conditions;

import java.util.List;
import java.util.function.Predicate;

public final class Lines {
  private static final Predicate<String> BLANK = String::isBlank;

  /// How many lines have something on them.
  public static long written(List<String> lines) {
    return lines.stream().filter(BLANK.negate()).count();
  }
}
