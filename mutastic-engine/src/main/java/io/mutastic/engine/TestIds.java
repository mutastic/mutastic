package io.mutastic.engine;

/// What the worker's name for a failure says: a test is `com.example.PricingTest#discount`, a class
/// that failed as a whole (its setup or teardown) is `com.example.PricingTest`, and anything else,
/// an engine's own failure, is the platform's unique id, `[engine:junit-jupiter]`.
final class TestIds {
  private TestIds() {}

  /// Whether it's a class that failed as a whole.
  static boolean isClass(String test) {
    return !test.contains("#") && !test.startsWith("[");
  }

  /// Whether it can be selected again: a test or a class, not an engine.
  static boolean canRunAgain(String test) {
    return !test.startsWith("[");
  }

  /// Whether `test` is one of `testClass`'s, or of a class nested in it.
  static boolean inClass(String test, String testClass) {
    return test.startsWith(testClass + "#") || test.startsWith(testClass + "$");
  }
}
