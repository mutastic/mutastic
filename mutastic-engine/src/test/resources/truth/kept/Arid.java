package kept;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;
import java.util.stream.Gatherers;

/// Each reason mutastic skips a mutant as arid, in the shape javac writes it: one method each. A
/// test puts `@DoMutate` on the class, or a `mutate:` rule over it, and every one comes back.
class Arid {
  private static final Logger LOG = Logger.getLogger("arid");

  private String name = "arid";
  private final List<String> seen = new ArrayList<>();

  static void printed(int a) {
    System.out.println(a);
  }

  static void logged(String who) {
    LOG.info("seen " + who);
  }

  static void timed(long start) {
    long took = System.currentTimeMillis() - start;
    LOG.info("took " + took + " ms");
  }

  static void thrown(int a) {
    if (a < 0) {
      throw new IllegalArgumentException("negative");
    }
  }

  static Object checked(Object a) {
    return Objects.requireNonNull(a, "a is needed");
  }

  static int asserted(int a) {
    assert a > 0;
    return a;
  }

  static List<String> sized(int n) {
    return new ArrayList<>(n * 2);
  }

  static Map<String, String> loaded(int n) {
    return new HashMap<>(n, 0.75f);
  }

  void grown(int n) {
    ((ArrayList<String>) seen).ensureCapacity(n + 16);
  }

  static void paused(int seconds) throws InterruptedException {
    Thread.sleep(seconds * 1000L);
  }

  static void pausedInUnits(int seconds) throws InterruptedException {
    TimeUnit.SECONDS.sleep(seconds + 1);
  }

  static void joined(Thread worker, int seconds) throws InterruptedException {
    worker.join(seconds * 1000L);
  }

  static void required(Object a) {
    Objects.requireNonNull(a);
  }

  static void classOf(Object a) {
    a.getClass();
  }

  static int[] array(int n) {
    return new int[4];
  }

  static int counted(int[] values) {
    int count = 0;
    for (int value : values) {
      if (value > 0) {
        count++;
      }
    }
    return count;
  }

  static String[] parts(String text) {
    return text.split(",", -1);
  }

  String getName() {
    return name;
  }

  void setName(String name) {
    this.name = name;
  }

  @Override
  public String toString() {
    return describe();
  }

  private String describe() {
    return "Arid " + name;
  }

  @Override
  public int hashCode() {
    return name.hashCode() * 31;
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof Arid that && that.name.equals(name);
  }

  static List<Integer> gathered(List<Integer> items) {
    return items.stream().gather(Gatherers.mapConcurrent(4, x -> x + 1)).toList();
  }

  /// The project's own skip, which `@DoMutate` on the class doesn't undo.
  @DoNotMutate("tested elsewhere")
  static List<String> tested(int n) {
    return new ArrayList<>(n * 2);
  }
}

@interface DoNotMutate {
  String value();
}
