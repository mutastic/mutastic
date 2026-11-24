package com.example.props;

/** Arithmetic on whole numbers. */
public final class Numbers {
  private Numbers() {}

  /** Twice {@code x}, widened so it can't overflow. */
  public static long twice(int x) {
    return (long) x * 2;
  }

  /** {@code x}, kept between {@code low} and {@code high}. */
  public static int clamp(int x, int low, int high) {
    if (x < low) {
      return low;
    }
    return Math.min(x, high);
  }
}
