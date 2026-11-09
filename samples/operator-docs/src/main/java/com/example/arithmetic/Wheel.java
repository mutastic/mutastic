package com.example.arithmetic;

/// A wheel of numbered slots, counted round from 0.
public final class Wheel {
  private final int slots;

  public Wheel(int slots) {
    this.slots = slots;
  }

  public int slotAfter(int steps) {
    return steps % slots;
  }
}
