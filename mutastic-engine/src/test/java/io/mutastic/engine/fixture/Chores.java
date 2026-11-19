package io.mutastic.engine.fixture;

import java.util.ArrayList;
import java.util.List;

/// Code each of whose mutants is arid, one reason a method: a log call, a wait, a capacity.
public final class Chores {
  private static final System.Logger LOG = System.getLogger("chores");

  private Chores() {}

  public static void logged(String name) {
    LOG.log(System.Logger.Level.INFO, name);
  }

  public static void pause(int seconds) throws InterruptedException {
    Thread.sleep(seconds * 1000L);
  }

  public static List<String> sized(int n) {
    return new ArrayList<>(n * 2);
  }
}
