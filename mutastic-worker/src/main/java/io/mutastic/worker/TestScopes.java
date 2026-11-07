package io.mutastic.worker;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.TestPlan;

/// Whose lines the coverage pass is recording: the test that's running, or between tests, the class
/// or the suite around them.
///
/// A class's `@BeforeAll` and `@AfterAll`, a shared instance's constructor, a parameterised test's
/// `@MethodSource`: none of them runs inside a test, and each can make what the tests read. So what
/// a container runs counts for every test under it, which is what [#credit] does once the plan has
/// finished and every test under it is known. Filed under whichever test ran before, as they once
/// were, those lines went to a test that never needed them. A test's own instance is made before
/// the test is said to start, and TestNG's `@BeforeMethod` runs before too, so those count for the
/// class. What runs before any of it counts for every test: TestNG makes its test classes as it
/// finds the tests, with no sign of which class is being made.
final class TestScopes implements TestExecutionListener {
  private final Function<TestIdentifier, String> id;
  private final boolean loadsTestClasses;

  private @Nullable TestPlan plan;

  /// @param id a test's id, the same for each of its cases
  /// @param loadsTestClasses whether the context class loader holds the test classes, so that a
  ///   class's static initialiser can be run as the class starts
  TestScopes(Function<TestIdentifier, String> id, boolean loadsTestClasses) {
    this.id = id;
    this.loadsTestClasses = loadsTestClasses;
  }

  @Override
  public void testPlanExecutionStarted(TestPlan testPlan) {
    plan = testPlan;
  }

  @Override
  public void executionStarted(TestIdentifier identifier) {
    Probes.startTest(scopeOf(identifier));
    if (loadsTestClasses && identifier.isContainer()) {
      identifier
          .getSource()
          .filter(ClassSource.class::isInstance)
          .map(ClassSource.class::cast)
          .ifPresent(TestScopes::initialise);
    }
  }

  @Override
  public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
    var testPlan = plan;
    if (testPlan != null) {
      Probes.startTest(testPlan.getParent(identifier).map(this::scopeOf).orElse(Probes.OUTSIDE));
    }
  }

  /// Otherwise a test class's static initialiser runs inside its first test, and counts for that
  /// test alone, though its fields are there for them all. One that throws throws again, as
  /// `NoClassDefFoundError`, where the tests use it: failing it is theirs to report.
  private static void initialise(ClassSource source) {
    try {
      Class.forName(source.getClassName(), true, Thread.currentThread().getContextClassLoader());
    } catch (ClassNotFoundException | LinkageError e) {
      // the framework's own account of a class it can't use comes with its tests
    }
  }

  /// Gives each container's lines and values to every test under it: call after the plan has
  /// finished, with the last lines flushed.
  void credit() {
    var testPlan = plan;
    if (testPlan != null) {
      var everyTest = new LinkedHashSet<String>();
      for (var root : testPlan.getRoots()) {
        everyTest.addAll(testsUnder(testPlan, root));
        credit(testPlan, root);
      }
      Probes.credit(Probes.OUTSIDE, everyTest);
    }
  }

  private void credit(TestPlan testPlan, TestIdentifier container) {
    Probes.credit(scopeOf(container), testsUnder(testPlan, container));
    for (var child : testPlan.getChildren(container)) {
      if (child.isContainer()) {
        credit(testPlan, child);
      }
    }
  }

  private Set<String> testsUnder(TestPlan testPlan, TestIdentifier container) {
    return testPlan.getDescendants(container).stream()
        .filter(TestIdentifier::isTest)
        .map(id)
        .collect(Collectors.toCollection(LinkedHashSet::new));
  }

  /// A test's id, or for a container its unique id, which no test's has.
  private String scopeOf(TestIdentifier identifier) {
    return identifier.isTest() ? id.apply(identifier) : identifier.getUniqueId();
  }
}
