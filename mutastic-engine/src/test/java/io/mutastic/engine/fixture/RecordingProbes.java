package io.mutastic.engine.fixture;

import java.util.ArrayList;
import java.util.List;

/// Stands in for the worker's probes in the engine's tests, so instrumented classes can run here
/// and be checked: it keeps every call. Named apart from the worker's `Probes`, so a run of
/// mutastic on itself can tell the two apart.
public final class RecordingProbes {
  public static final List<Integer> LINES = new ArrayList<>();
  public static final List<long[]> VALUES = new ArrayList<>();

  private RecordingProbes() {}

  public static void line(int lineId) {
    LINES.add(lineId);
  }

  public static void value(int parameterId, long value) {
    VALUES.add(new long[] {parameterId, value});
  }
}
