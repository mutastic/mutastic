package io.mutastic.worker;

import java.util.HashSet;
import java.util.function.Function;
import java.util.function.IntConsumer;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/// How many tests a run will report, said as it starts, so progress can count them off: each test
/// by its id, as [TestTimes] reports them, a parameterised test's cases as one.
final class PlannedTests implements TestExecutionListener {
  private final Function<TestIdentifier, String> id;
  private final IntConsumer planned;

  /// @param id a test's id, the same for each of its cases
  /// @param planned hears how many tests there are, once, before any runs
  PlannedTests(Function<TestIdentifier, String> id, IntConsumer planned) {
    this.id = id;
    this.planned = planned;
  }

  @Override
  public void testPlanExecutionStarted(TestPlan testPlan) {
    var tests = new HashSet<String>();
    for (var root : testPlan.getRoots()) {
      for (var identifier : testPlan.getDescendants(root)) {
        if (identifier.isTest() || identifier.getSource().orElse(null) instanceof MethodSource) {
          tests.add(id.apply(identifier));
        }
      }
    }
    planned.accept(tests.size());
  }
}
