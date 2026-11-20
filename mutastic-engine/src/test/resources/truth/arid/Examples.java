package arid;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/// Each arid rule's example, as the docs show it, in a method named for the rule: `waits` for
/// `mutastic:waits`. The example is the rule's line, word for word.
class Examples {
  private final String name;
  private final int id;

  Examples(String name, int id) {
    this.name = name;
    this.id = id;
  }

  /// `mutastic:trivial-accessors`
  String getName() { return name; }

  /// `mutastic:debug-representations`
  @Override
  public String toString() { return "Order " + id; }

  void output() {
    System.out.println("Saved " + id);
  }

  void messages(int amount) {
    if (amount <= 0) {
      throw new IllegalArgumentException("amount must be positive");
    }
  }

  void failedAsserts(int total) {
    assert total >= 0 : "negative total";
  }

  List<String> capacity(List<String> orders) {
    List<String> sized = new ArrayList<>(orders.size() * 2);
    sized.addAll(orders);
    return sized;
  }

  void waitTimes(int seconds) throws InterruptedException {
    Thread.sleep(seconds * 1000L);
  }

  void waits() throws InterruptedException {
    Thread.sleep(100);
  }

  void nullChecks(Object order) {
    Objects.requireNonNull(order);
  }

  byte[] arraySizes() {
    byte[] buffer = new byte[8192];
    return buffer;
  }

  String[] splitLimits(String line) {
    return line.split(",", 2);
  }

  void threadNames(Thread thread) {
    thread.setName("background-task");
  }

  void threadPriorities(Thread thread) {
    thread.setPriority(Thread.MAX_PRIORITY);
  }

  void keepAlive(ThreadPoolExecutor pool) {
    pool.setKeepAliveTime(30, TimeUnit.SECONDS);
  }

  Thread stackSizes(Runnable task) {
    return Thread.ofPlatform().stackSize(1 << 20).unstarted(task);
  }
}
