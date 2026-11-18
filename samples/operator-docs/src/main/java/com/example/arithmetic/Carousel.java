package com.example.arithmetic;

/// A carousel of slides that wraps round at both ends.
public final class Carousel {
  private Carousel() {}

  /// The slide shown after moving `steps` from the first, either way.
  public static int slide(int steps, int count) {
    return Math.floorMod(steps, count);
  }
}
