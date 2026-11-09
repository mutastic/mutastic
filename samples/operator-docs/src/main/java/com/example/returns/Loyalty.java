package com.example.returns;

/// A point for every so much spent.
public final class Loyalty {
  private final int perPoint;

  public Loyalty(int perPoint) {
    this.perPoint = perPoint;
  }

  public int points(int spent) {
    int points = spent / perPoint;
    return points;
  }
}
