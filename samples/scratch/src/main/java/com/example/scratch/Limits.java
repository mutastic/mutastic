package com.example.scratch;

public class Limits {
  /// Whether `size` fits in a buffer of `capacity`.
  public boolean fits(int size, int capacity) {
    return size <= capacity;
  }
}
