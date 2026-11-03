package io.mutastic.worker;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/// What instrumented code calls during the coverage pass. The engine instruments every project
/// class to call `Probes.line(id)` at the start of each source line, and `Probes.value(id, v)` with
/// each whole-number argument when a method starts.
///
/// A class's static initialiser also calls [#loading] as it starts and [#loaded] as it returns, so
/// lines that run only while a class loads can be told apart: a mutant swapped into a class that's
/// already loaded never reaches them.
///
/// Probes run on every executed line, so they must be nearly free: a line probe sets one element of
/// the running test's `boolean[]`, as JaCoCo does, and the array is filed under the test when the
/// next one starts. Values are capped per parameter per test, so a loop calling a method a million
/// times records [#VALUES_PER_PARAMETER] of them.
public final class Probes {
  private static final String OUTSIDE = "(outside any test)";
  static final int VALUES_PER_PARAMETER = 16;

  // A framework (Quarkus) may load its own copy of this class for the code it loads, with its own
  // statics. So the state lives in the system properties, which every copy shares, as plain
  // java.util objects any copy can use.
  private static final Map<String, Set<Integer>> LINES_BY_TEST =
      shared("lines", ConcurrentHashMap::new);
  private static final Map<String, Map<Integer, Set<Long>>> VALUES_BY_TEST =
      shared("values", ConcurrentHashMap::new);
  private static final AtomicReference<Map<Integer, Set<Long>>> VALUES =
      shared("current-values", () -> new AtomicReference<>(new ConcurrentHashMap<>()));
  private static final AtomicReference<String> TEST =
      shared("test", () -> new AtomicReference<>(OUTSIDE));
  private static final AtomicReference<boolean[]> HITS =
      shared("hits", () -> new AtomicReference<>(new boolean[1 << 12]));

  /// Lines run with no static initialiser running, in any test.
  private static final AtomicReference<boolean[]> OUTSIDE_LOADING =
      shared("outside-loading", () -> new AtomicReference<>(new boolean[1 << 12]));

  /// Static initialisers running now, on any thread. One running on another thread makes a line
  /// look as if it ran only at class load, which costs that line's mutants a fresh class loader,
  /// never a wrong result.
  private static final AtomicInteger LOADING = shared("loading", AtomicInteger::new);

  private Probes() {}

  /// The state every copy of this class shares, `mutastic.probes.<key>`, made by the first.
  @SuppressWarnings("unchecked")
  private static <T> T shared(String key, Supplier<T> create) {
    var properties = System.getProperties();
    synchronized (properties) {
      return (T) properties.computeIfAbsent("mutastic.probes." + key, ignored -> create.get());
    }
  }

  /// Called at the start of every source line of the project's code.
  public static void line(int lineId) {
    mark(HITS, lineId);
    if (LOADING.get() == 0) {
      mark(OUTSIDE_LOADING, lineId);
    }
  }

  private static void mark(AtomicReference<boolean[]> hits, int lineId) {
    var current = hits.get();
    if (lineId < current.length) {
      current[lineId] = true;
    } else {
      grow(hits, lineId)[lineId] = true;
    }
  }

  /// Called as a static initialiser starts.
  public static void loading() {
    LOADING.incrementAndGet();
  }

  /// Called as a static initialiser returns. One that throws never calls it: the next test starts
  /// the count again.
  public static void loaded() {
    LOADING.decrementAndGet();
  }

  /// Called when a method starts, with one whole-number argument (`int`, `long`, `short`, `byte` or
  /// `char`).
  public static void value(int parameterId, long value) {
    var current = VALUES.get();
    var seen = current.get(parameterId);
    if (seen == null) {
      seen = current.computeIfAbsent(parameterId, id -> ConcurrentHashMap.newKeySet());
    }
    if (seen.size() < VALUES_PER_PARAMETER) {
      seen.add(value);
    }
  }

  /// Files the running test's lines, and starts recording for `name`.
  static void startTest(String name) {
    synchronized (HITS) {
      flush();
      TEST.set(name);
      LOADING.set(0);
    }
  }

  /// Files the running test's lines: at a test's start, and at the end of the coverage pass.
  static void flush() {
    synchronized (HITS) {
      fileHits();
      fileValues();
    }
  }

  private static void fileHits() {
    var current = HITS.get();
    Set<Integer> lines =
        LINES_BY_TEST.computeIfAbsent(TEST.get(), name -> ConcurrentHashMap.newKeySet());
    for (int id = 0; id < current.length; id++) {
      if (current[id]) {
        lines.add(id);
      }
    }
    HITS.set(new boolean[current.length]);
  }

  private static void fileValues() {
    var recorded = VALUES.get();
    if (!recorded.isEmpty()) {
      var forTest = VALUES_BY_TEST.computeIfAbsent(TEST.get(), name -> new ConcurrentHashMap<>());
      recorded.forEach(
          (id, seen) ->
              forTest.computeIfAbsent(id, ignored -> ConcurrentHashMap.newKeySet()).addAll(seen));
    }
    VALUES.set(new ConcurrentHashMap<>());
  }

  static void reset() {
    synchronized (HITS) {
      LINES_BY_TEST.clear();
      VALUES_BY_TEST.clear();
      VALUES.set(new ConcurrentHashMap<>());
      TEST.set(OUTSIDE);
      HITS.set(new boolean[HITS.get().length]);
      OUTSIDE_LOADING.set(new boolean[OUTSIDE_LOADING.get().length]);
      LOADING.set(0);
    }
  }

  static Map<String, Set<Integer>> linesByTest() {
    return LINES_BY_TEST;
  }

  static Map<String, Map<Integer, Set<Long>>> valuesByTest() {
    return VALUES_BY_TEST;
  }

  /// The lines some test ran, but only ever while a class was loading.
  static Set<Integer> onlyAtLoad() {
    var outside = OUTSIDE_LOADING.get();
    var lines = new java.util.TreeSet<Integer>();
    LINES_BY_TEST
        .values()
        .forEach(
            hit ->
                hit.forEach(
                    id -> {
                      if (id >= outside.length || !outside[id]) {
                        lines.add(id);
                      }
                    }));
    return lines;
  }

  private static boolean[] grow(AtomicReference<boolean[]> hits, int lineId) {
    synchronized (HITS) {
      var current = hits.get();
      if (lineId >= current.length) {
        var bigger = new boolean[Math.max(current.length * 2, lineId + 1)];
        System.arraycopy(current, 0, bigger, 0, current.length);
        hits.set(bigger);
        return bigger;
      }
      return current;
    }
  }
}
