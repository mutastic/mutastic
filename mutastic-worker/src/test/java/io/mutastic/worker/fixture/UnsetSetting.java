package io.mutastic.worker.fixture;

/// A class whose static initialiser fails: `AgentTest` checks the worker can tell.
public final class UnsetSetting {
  public static final int PORT = Integer.parseInt(System.getProperty("mutastic.fixture.unset"));

  private UnsetSetting() {}
}
