package threads;

import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/// Thread and pool settings in each shape javac writes them: what only names a thread, sets its
/// priority, its stack or how long a pool's idle threads live, and what decides whether, when or
/// how often work runs.
class Settings {
  static Thread named(Runnable task) {
    var thread = new Thread(task);
    thread.setName("background-task");
    return thread;
  }

  static Thread namedAtBirth(Runnable task) {
    return new Thread(task, "squarer");
  }

  static ThreadFactory lambdaFactory(String prefix) {
    var count = new AtomicInteger();
    return task -> new Thread(task, prefix + "-" + count.incrementAndGet());
  }

  static ThreadFactory referenceFactory() {
    return Settings::worker;
  }

  static Thread worker(Runnable task) {
    return new Thread(task, "ref-worker");
  }

  static Thread formatted(Runnable task, int number) {
    return new Thread(task, String.format("pool-%d", number));
  }

  static Thread built(Runnable task) {
    return Thread.ofPlatform().name("plat-", 0).daemon(true).unstarted(task);
  }

  static ThreadFactory virtual() {
    return Thread.ofVirtual().name("virt-", 0).factory();
  }

  /// A `null` name throws: the parameter's mutant runs.
  static Thread givenName(Runnable task, String name) {
    return new Thread(task, name);
  }

  /// The parameter is read for more than the name.
  static Thread prefixed(Runnable task, String prefix) {
    return new Thread(task, prefix.isEmpty() ? "anonymous" : prefix + "-1");
  }

  static Thread urgent(Runnable task) {
    var thread = new Thread(task);
    thread.setPriority(Thread.MAX_PRIORITY);
    return thread;
  }

  static Thread lowly(Runnable task) {
    return Thread.ofPlatform().priority(3).unstarted(task);
  }

  static ThreadPoolExecutor unbounded() {
    return new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
  }

  static ThreadPoolExecutor bounded() {
    return new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(100));
  }

  static ThreadPoolExecutor handedOff() {
    return new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new SynchronousQueue<>());
  }

  static void idle(ThreadPoolExecutor pool) {
    pool.setKeepAliveTime(10, TimeUnit.SECONDS);
  }

  static Thread deep(Runnable task) {
    return new Thread(null, task, "deep", 1 << 20);
  }

  static Thread builtDeep(Runnable task) {
    return Thread.ofPlatform().stackSize(1 << 20).unstarted(task);
  }

  static Thread fireAndForget(Runnable task) {
    var thread = new Thread(task);
    thread.setDaemon(true);
    thread.start();
    return thread;
  }

  static void stop(Thread thread) {
    thread.interrupt();
  }

  static void awaited(Thread thread) throws InterruptedException {
    thread.join(1000);
  }

  static void pause() throws InterruptedException {
    Thread.sleep(10);
  }

  static ExecutorService fixed() {
    return Executors.newFixedThreadPool(3);
  }

  static void logged() {
    System.out.println("on " + Thread.currentThread().getName());
  }
}
