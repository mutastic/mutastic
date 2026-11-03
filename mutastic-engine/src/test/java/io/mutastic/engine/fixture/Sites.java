package io.mutastic.engine.fixture;

import java.util.ArrayList;
import java.util.List;

/// Code whose mutants are judged by the instructions around them.
public final class Sites {
  private Sites() {}

  public static int largest(int[] values) {
    int max = 0;
    for (int value : values) {
      if (value > max) {
        max = value;
      }
    }
    return max;
  }

  public static int countAbove(int[] values, int floor) {
    int count = 0;
    for (int value : values) {
      if (value > floor) {
        count = value;
        count++;
      }
    }
    return count;
  }

  public static int smaller(int a, int b) {
    return a < b ? a : b;
  }

  public static int firstOrNone(int a, int b) {
    return a < b ? a : 0;
  }

  public static List<String> sized(int n) {
    return new ArrayList<>(n * 2);
  }

  public static int doubled(int n) {
    return n * 2;
  }

  public static void pause(int seconds) throws InterruptedException {
    Thread.sleep(seconds * 1000L);
  }
}
