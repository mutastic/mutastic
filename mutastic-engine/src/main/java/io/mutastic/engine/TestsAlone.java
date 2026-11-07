package io.mutastic.engine;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/// Whether the tests that catch mutants pass on their own with no mutant in.
///
/// A mutant runs against tests one at a time, and the coverage pass ran them together: so a test
/// that needs what another leaves behind fails against every mutant it meets, and each would look
/// caught. A class's setup makes this far likelier to matter, as it puts every test of the class in
/// front of what the setup reaches, not only those that reach it themselves. So a test is checked
/// the first time it catches something, and a test that fails with nothing changed has caught
/// nothing, then or afterwards. That's one more run for each test that ever catches, not for each
/// catch.
final class TestsAlone {
  /// By test, whether it fails. Two workers may check one test: that's a run wasted, no more.
  private final Map<String, Boolean> checked = new ConcurrentHashMap<>();

  boolean fails(String test, TestWorker worker, Duration timeout) {
    var known = checked.get(test);
    if (known != null) {
      return known;
    }
    boolean fails = worker.failsAlone(test, timeout);
    checked.put(test, fails);
    return fails;
  }

  List<String> withoutThoseFailing(List<String> tests) {
    return tests.stream().filter(test -> !checked.getOrDefault(test, false)).toList();
  }

  /// Those found to fail so far, in a stable order.
  List<String> failing() {
    return checked.entrySet().stream()
        .filter(Map.Entry::getValue)
        .map(Map.Entry::getKey)
        .sorted()
        .toList();
  }
}
