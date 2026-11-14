package io.mutastic.worker;

import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;

/// How many test cases a pass ran, each row of a parameterized test one, and how many it skipped:
/// those a condition or `@Disabled` turned off, each a test or a container, and those an assumption
/// aborted. The gate compares them with the task's start.
final class TestCases implements TestExecutionListener {
  private int ran;
  private int skipped;

  @Override
  public void executionSkipped(TestIdentifier identifier, String reason) {
    skipped++;
  }

  @Override
  public void executionFinished(TestIdentifier identifier, TestExecutionResult result) {
    if (!identifier.isTest()) {
      return;
    }
    if (result.getStatus() == TestExecutionResult.Status.ABORTED) {
      skipped++;
    } else {
      ran++;
    }
  }

  int ran() {
    return ran;
  }

  int skipped() {
    return skipped;
  }
}
