package io.mutastic.engine;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/// A worker made only once one is needed, and closed with what made it: a check after the mutants
/// that finds nothing to run again starts none.
final class LazyWorker implements AutoCloseable {
  private final Supplier<TestWorker> make;
  private @Nullable TestWorker made;

  LazyWorker(Supplier<TestWorker> make) {
    this.make = make;
  }

  /// The worker, made the first time it's asked for.
  TestWorker get() {
    var current = made;
    if (current == null) {
      current = make.get();
      made = current;
    }
    return current;
  }

  @Override
  public void close() {
    var current = made;
    if (current != null) {
      current.close();
    }
  }
}
