package io.mutastic.worker.fixture;

import java.util.function.IntSupplier;

/// Shaped like a test with state: a static counter, a nested class and a lambda that refer back
/// to it by name.
public class CounterTest {
  static int created;

  public CounterTest() {
    created++;
  }

  public static int created() {
    return created;
  }

  public static IntSupplier reader() {
    return new Nested()::read;
  }

  static final class Nested {
    int read() {
      return CounterTest.created;
    }
  }
}
