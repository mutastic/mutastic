package com.example.setup;

/// Each fraction is reached only by a test of one flaky class, which checks nothing of it: a catch
/// there could only be its class's setup failing.
public class Prices {
  public static int doubled(int x) {
    return x * 2;
  }

  public static int halved(int x) {
    return x / 2;
  }

  public static int thirded(int x) {
    return x / 3;
  }

  public static int quartered(int x) {
    return x / 4;
  }

  public static int fifthed(int x) {
    return x / 5;
  }
}
