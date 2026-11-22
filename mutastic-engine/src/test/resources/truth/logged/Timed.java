package logged;

import java.time.Duration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/// Values worked out for a log line. In a method named `only…`, every value a mutant changes goes
/// nowhere but a log call or a print, so each such mutant is arid. In one named `kept…`, the value
/// reaches something else too, on some path, so each stays counted.
class Timed {
  private static final Logger JUL = Logger.getLogger("logged");

  /// Something a test could check, that a value reaching it makes worth testing.
  interface Metrics {
    void record(String name, long value);
  }

  private final org.slf4j.Logger log;
  private final Metrics metrics;
  private boolean slow;
  private long last;

  Timed(org.slf4j.Logger log, Metrics metrics) {
    this.log = log;
    this.metrics = metrics;
  }

  void onlyInline(long start) {
    log.info("took {}", System.currentTimeMillis() - start);
  }

  void onlyWrapped(long start) {
    log.info(
        "took {}",
        System.currentTimeMillis()
            - start);
  }

  void onlyThroughALocal(long start) {
    long took = System.currentTimeMillis() - start;
    log.info("took {} ms", took);
  }

  void onlyReadTwice(int a) {
    int doubled = a * 2;
    log.info("{}", doubled);
    log.debug("again {}", doubled);
  }

  void onlyReassigned(long start, boolean timed) {
    long took = -1;
    if (timed) {
      took = System.currentTimeMillis() - start;
    }
    log.info("took {}", took);
  }

  void onlyADuration(long start) {
    log.info("took {}", Duration.ofMillis(System.currentTimeMillis() - start));
  }

  void onlyASize(List<String> items) {
    log.info("{} left", items.size() - 1);
  }

  void onlyConcatenated(int a, int b) {
    System.out.println("sum " + (a + b));
  }

  void onlyConcatenatedThroughALocal(int a, int b) {
    String line = "sum " + (a + b);
    log.info(line);
  }

  void onlyVarargs(int a, int b) {
    log.info("{} {} {}", a + 1, b + 1, a - b);
  }

  void onlyJul(int a) {
    JUL.log(Level.INFO, "{0}", a * 3);
  }

  void onlyFormatted(int a) {
    System.out.printf("%d%n", a % 7);
  }

  void onlyARatio(int free, int all) {
    int ratio = free * 100 / Math.max(all, 1);
    System.out.printf("%d%% free%n", ratio);
  }

  void onlyAFormatString(int a) {
    log.info(String.format("%d left", a - 1));
  }

  void onlyChosen(int a, boolean up) {
    log.info("{}", up ? a + 1 : a - 1);
  }

  void onlyInACatch(int a, Runnable work) {
    int before = a - 1;
    try {
      work.run();
    } catch (RuntimeException e) {
      log.warn("failed after {}", before);
    }
  }

  void onlyInALoop(List<String> items, long start) {
    for (String item : items) {
      long took = System.nanoTime() - start;
      log.info("{} took {}", item, took);
    }
  }

  void onlyAfterACallThatDoesSomething(java.util.concurrent.atomic.AtomicInteger calls) {
    log.info("call {}", calls.incrementAndGet() * 2);
  }

  long keptReturned(long start) {
    long took = System.currentTimeMillis() - start;
    log.info("took {}", took);
    return took;
  }

  void keptCompared(long start) {
    long took = System.currentTimeMillis() - start;
    log.info("took {}", took);
    if (took > 1000) {
      slow = true;
    }
  }

  void keptRecorded(long start) {
    long took = System.currentTimeMillis() - start;
    log.info("took {} ms", took);
    metrics.record("checkout.ms", took);
  }

  void keptInALoop(List<String> items, long start, long limit) {
    for (String item : items) {
      long took = System.nanoTime() - start;
      log.info("{}", took);
      if (took > limit) {
        break;
      }
    }
  }

  void keptInAField(long start) {
    long took = System.currentTimeMillis() - start;
    log.info("took {}", took);
    last = took;
  }

  int keptARatioReturned(int free, int all) {
    int ratio = free * 100 / Math.max(all, 1);
    System.out.printf("%d%% free%n", ratio);
    return ratio;
  }

  void keptPassedOn(int a) {
    log.info("{}", described(a * 2));
  }

  void keptInABuilderOfItsOwn(int a) {
    StringBuilder text = new StringBuilder();
    text.append(a + 1);
    log.info(text.toString());
  }

  void keptCapturedByALambda(int a) {
    int next = a + 1;
    JUL.fine(() -> "next " + next);
  }

  void keptInALogAndACheck(int a) {
    int left = a - 1;
    log.info("{} left", left);
    if (left < 0) {
      throw new IllegalStateException();
    }
  }

  private static String described(int value) {
    return "value " + value;
  }

  boolean slow() {
    return slow;
  }

  long last() {
    return last;
  }
}
