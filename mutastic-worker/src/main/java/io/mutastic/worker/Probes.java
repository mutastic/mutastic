package io.mutastic.worker;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/// What instrumented code calls during the coverage pass. The engine instruments every project
/// class to call `Probes.line(id)` at the start of each source line, and `Probes.value(id, v)`
/// with each whole-number argument when a method starts.
///
/// Probes run on every executed line, so they must be nearly free: a line probe sets one
/// element of the running test's `boolean[]`, as JaCoCo does, and the array is filed under the
/// test when the next one starts. Values are capped per parameter per test, so a loop calling a
/// method a million times records [#VALUES_PER_PARAMETER] of them.
public final class Probes {
  private static final String OUTSIDE = "(outside any test)";
  static final int VALUES_PER_PARAMETER = 16;
  private static final Map<String, Set<Integer>> LINES_BY_TEST = new ConcurrentHashMap<>();
  private static final Map<String, Map<Integer, Set<Long>>> VALUES_BY_TEST = new ConcurrentHashMap<>();
  private static volatile Map<Integer, Set<Long>> values = new ConcurrentHashMap<>();
  private static volatile String test = OUTSIDE;
  private static volatile boolean[] hits = new boolean[1 << 12];

  private Probes() {}

  /// Called at the start of every source line of the project's code.
  public static void line(int lineId) {
    var current = hits;
    if (lineId < current.length) {
      current[lineId] = true;
    } else {
      grow(lineId)[lineId] = true;
    }
  }

  /// Called when a method starts, with one whole-number argument (`int`, `long`, `short`,
  /// `byte` or `char`).
  public static void value(int parameterId, long value) {
    var seen = values.get(parameterId);
    if (seen == null) {
      seen = values.computeIfAbsent(parameterId, id -> ConcurrentHashMap.newKeySet());
    }
    if (seen.size() < VALUES_PER_PARAMETER) {
      seen.add(value);
    }
  }

  /// Files the running test's lines, and starts recording for `name`.
  static synchronized void startTest(String name) {
    flush();
    test = name;
  }

  /// Files the running test's lines: at a test's start, and at the end of the coverage pass.
  static synchronized void flush() {
    var current = hits;
    Set<Integer> lines = LINES_BY_TEST.computeIfAbsent(test, name -> ConcurrentHashMap.newKeySet());
    for (int id = 0; id < current.length; id++) {
      if (current[id]) {
        lines.add(id);
      }
    }
    hits = new boolean[current.length];
    var recorded = values;
    if (!recorded.isEmpty()) {
      var forTest = VALUES_BY_TEST.computeIfAbsent(test, name -> new ConcurrentHashMap<>());
      recorded.forEach((id, seen) -> forTest.computeIfAbsent(id, ignored -> ConcurrentHashMap.newKeySet()).addAll(seen));
    }
    values = new ConcurrentHashMap<>();
  }

  static synchronized void reset() {
    LINES_BY_TEST.clear();
    VALUES_BY_TEST.clear();
    values = new ConcurrentHashMap<>();
    test = OUTSIDE;
    hits = new boolean[hits.length];
  }

  static Map<String, Set<Integer>> linesByTest() {
    return LINES_BY_TEST;
  }

  static Map<String, Map<Integer, Set<Long>>> valuesByTest() {
    return VALUES_BY_TEST;
  }

  private static synchronized boolean[] grow(int lineId) {
    var current = hits;
    if (lineId >= current.length) {
      var bigger = new boolean[Math.max(current.length * 2, lineId + 1)];
      System.arraycopy(current, 0, bigger, 0, current.length);
      hits = bigger;
      return bigger;
    }
    return current;
  }
}
