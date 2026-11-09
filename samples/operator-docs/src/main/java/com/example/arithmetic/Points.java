package com.example.arithmetic;

public final class Points {
  /// Fails rather than wrap round when the points don't fit.
  public static int add(int points, int more) {
    return Math.addExact(points, more);
  }
}
