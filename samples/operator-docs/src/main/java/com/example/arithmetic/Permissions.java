package com.example.arithmetic;

/// Permissions as bits: 1 to read, 2 to write, 4 to delete.
public final class Permissions {
  public static int grant(int held, int granted) {
    return held | granted;
  }
}
