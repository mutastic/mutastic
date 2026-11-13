package io.mutastic.worker;

import java.util.function.Consumer;
import java.util.function.Function;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/// Each test as it starts, by its id, so progress can say which one a long coverage pass is in. A
/// parameterised test's cases each start it again.
final class TestsStarting implements TestExecutionListener {
  private final Function<TestIdentifier, String> id;
  private final Consumer<String> started;

  /// @param id a test's id, the same for each of its cases
  /// @param started hears each test's id as it starts
  TestsStarting(Function<TestIdentifier, String> id, Consumer<String> started) {
    this.id = id;
    this.started = started;
  }

  @Override
  public void executionStarted(TestIdentifier identifier) {
    if (identifier.isTest()) {
      started.accept(id.apply(identifier));
    }
  }
}
