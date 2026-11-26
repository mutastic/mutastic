package com.example.cache;

/// A class whose name starts with `$`, as generated code's often do, though this one isn't.
public final class $Cache {
  private $Cache() {}

  public static int size(int entries) {
    return entries > 10 ? 10 : entries;
  }
}
