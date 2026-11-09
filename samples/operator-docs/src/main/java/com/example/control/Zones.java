package com.example.control;

/// Days to deliver to each zone: 1 is near, 2 is far, and the rest are abroad.
public final class Zones {
  private final int near;
  private final int far;
  private final int abroad;

  public Zones(int near, int far, int abroad) {
    this.near = near;
    this.far = far;
    this.abroad = abroad;
  }

  public int days(int zone) {
    return switch (zone) {
      case 1 -> near;
      case 2 -> far;
      default -> abroad;
    };
  }
}
