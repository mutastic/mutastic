package io.mutastic.engine.fixture;

import java.util.ArrayList;
import java.util.List;

/// [Chores] under `@DoMutate`: its arid mutants come back, but for a method's own `@DoNotMutate`.
@Kept.DoMutate
public final class KeptChores {
  private static final System.Logger LOG = System.getLogger("chores");

  private KeptChores() {}

  public static void logged(String name) {
    LOG.log(System.Logger.Level.INFO, name);
  }

  public static void pause(int seconds) throws InterruptedException {
    Thread.sleep(seconds * 1000L);
  }

  public static List<String> sized(int n) {
    return new ArrayList<>(n * 2);
  }

  @OwnAnnotations.DoNotMutate(reason = "tested elsewhere")
  public static List<String> skipped(int n) {
    return new ArrayList<>(n * 2);
  }
}
