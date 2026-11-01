package io.mutastic.worker;

import java.util.ArrayList;
import java.util.List;

/// Stands in for the worker's probes in the engine's tests, so instrumented classes can run here
/// and be checked: it keeps every call.
public final class Probes {
  public static final List<Integer> LINES = new ArrayList<>();
  public static final List<long[]> VALUES = new ArrayList<>();

  private Probes() {}

  public static void line(int lineId) {
    LINES.add(lineId);
  }

  public static void value(int parameterId, long value) {
    VALUES.add(new long[] {parameterId, value});
  }
}
