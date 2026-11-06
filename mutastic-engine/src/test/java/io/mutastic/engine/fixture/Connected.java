package io.mutastic.engine.fixture;

/// Conditions of two tests, for the connector operator: some it swaps, one it mustn't.
public class Connected {
  public int both(int a, int b) {
    if (a > 0 && b > 0) {
      return 1;
    }
    return 2;
  }

  public boolean either(int a, int b) {
    return a > 0 || b > 0;
  }

  /// The second test reads what the first one's passing binds: `||` would read it unset.
  public int bound(Object value, int b) {
    if (value instanceof Integer number && number > b) {
      return 1;
    }
    return 2;
  }
}
