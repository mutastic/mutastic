package com.example.arguments;

public final class Volume {
  private final int min;
  private final int max;

  public Volume(int min, int max) {
    this.min = min;
    this.max = max;
  }

  /// `level`, kept between the quietest and the loudest.
  public int set(int level) {
    return clamp(level, min, max);
  }

  static int clamp(int value, int low, int high) {
    return Math.max(low, Math.min(value, high));
  }
}
