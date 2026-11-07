package io.mutastic.worker;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.BiConsumer;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/// How long each test takes when a mutant run runs it alone, for its timeout: all its cases (a
/// parameterised test runs every one), plus its class's setup. Each mutant run loads a fresh copy
/// of the test class, so every run pays that setup again: static initialisers, `@BeforeAll`, a
/// Spring context. A class's tests are reported when the class finishes.
///
/// The setup is what the class took beyond its tests. A nested class counts its own, not its
/// enclosing class's.
final class TestTimes implements TestExecutionListener {
  private final Function<TestIdentifier, String> id;
  private final BiConsumer<String, Long> finished;

  private final Map<String, Long> startedAt = new HashMap<>();

  /// By class container: each test's id and the nanoseconds its cases took.
  private final Map<String, Map<String, Long>> byClass = new HashMap<>();

  private @Nullable TestPlan plan;

  /// @param id a test's id, the same for each of its cases
  /// @param finished hears each test once, with its nanoseconds
  TestTimes(Function<TestIdentifier, String> id, BiConsumer<String, Long> finished) {
    this.id = id;
    this.finished = finished;
  }

  @Override
  public void testPlanExecutionStarted(TestPlan testPlan) {
    plan = testPlan;
  }

  @Override
  public void executionStarted(TestIdentifier identifier) {
    startedAt.put(identifier.getUniqueId(), System.nanoTime());
  }

  @Override
  public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
    long took =
        System.nanoTime() - startedAt.getOrDefault(identifier.getUniqueId(), System.nanoTime());
    if (identifier.isTest()) {
      var tests = byClass.computeIfAbsent(classOf(identifier), ignored -> new LinkedHashMap<>());
      tests.merge(id.apply(identifier), took, Long::sum);
      return;
    }
    var tests = byClass.remove(identifier.getUniqueId());
    if (tests != null) {
      long setup = Math.max(0, took - tests.values().stream().mapToLong(Long::longValue).sum());
      tests.forEach((test, nanos) -> finished.accept(test, nanos + setup));
    }
  }

  @Override
  public void testPlanExecutionFinished(TestPlan testPlan) {
    // Tests with no class above them: no setup to share.
    byClass.values().forEach(tests -> tests.forEach(finished));
    byClass.clear();
  }

  /// The unique id of the nearest class above the test, or "" when there is none.
  private String classOf(TestIdentifier test) {
    var current = parentOf(test);
    while (current.isPresent()) {
      var parent = current.get();
      if (parent.getSource().filter(ClassSource.class::isInstance).isPresent()) {
        return parent.getUniqueId();
      }
      current = parentOf(parent);
    }
    return "";
  }

  private Optional<TestIdentifier> parentOf(TestIdentifier test) {
    var testPlan = plan;
    return testPlan == null ? Optional.empty() : testPlan.getParent(test);
  }
}
