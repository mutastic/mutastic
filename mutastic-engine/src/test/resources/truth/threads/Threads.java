package threads;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/// Each method's thread call, and as `xClaim` the test its card says catches the call taken away,
/// or its wait that never times out: `true` when that test passes. Each test waits a short time at
/// most, so a mutant that makes it wait for ever fails it instead.
class Threads {
  private static final long WAIT = 200;

  // Threads started: join the thread, or wait on a latch its task counts down, then look.

  static Thread started(AtomicInteger done) {
    Thread thread = new Thread(done::incrementAndGet);
    thread.start();
    return thread;
  }

  static boolean startedClaim(String a, String b) throws InterruptedException {
    var done = new AtomicInteger();
    started(done).join();
    return done.get() == 1;
  }

  static void startedInline(CountDownLatch done) {
    new Thread(done::countDown).start();
  }

  static boolean startedInlineClaim(String a, String b) throws InterruptedException {
    var done = new CountDownLatch(1);
    startedInline(done);
    return done.await(WAIT, TimeUnit.MILLISECONDS);
  }

  static void startedByABuilder(CountDownLatch done) {
    Thread.ofPlatform().name("worker").start(done::countDown);
  }

  static boolean startedByABuilderClaim(String a, String b) throws InterruptedException {
    var done = new CountDownLatch(1);
    startedByABuilder(done);
    return done.await(WAIT, TimeUnit.MILLISECONDS);
  }

  static void startedVirtual(CountDownLatch done) {
    Thread.startVirtualThread(done::countDown);
  }

  static boolean startedVirtualClaim(String a, String b) throws InterruptedException {
    var done = new CountDownLatch(1);
    startedVirtual(done);
    return done.await(WAIT, TimeUnit.MILLISECONDS);
  }

  static void startedInALambda(CountDownLatch done) {
    Runnable starter =
        () -> {
          Thread thread = new Thread(done::countDown);
          thread.start();
        };
    starter.run();
  }

  static boolean startedInALambdaClaim(String a, String b) throws InterruptedException {
    var done = new CountDownLatch(1);
    startedInALambda(done);
    return done.await(WAIT, TimeUnit.MILLISECONDS);
  }

  // Work handed to a pool: wait on a latch, or shut the pool down and wait for it, then look.

  static void executed(ExecutorService pool, AtomicInteger done) {
    pool.execute(done::incrementAndGet);
  }

  static boolean executedClaim(String a, String b) throws InterruptedException {
    var pool = Executors.newSingleThreadExecutor();
    var done = new AtomicInteger();
    executed(pool, done);
    pool.shutdown();
    return pool.awaitTermination(WAIT, TimeUnit.MILLISECONDS) && done.get() == 1;
  }

  static void executedByAnExecutor(Executor executor, CountDownLatch done) {
    executor.execute(done::countDown);
  }

  static boolean executedByAnExecutorClaim(String a, String b) throws InterruptedException {
    var pool = Executors.newCachedThreadPool();
    var done = new CountDownLatch(1);
    executedByAnExecutor(pool, done);
    boolean ran = done.await(WAIT, TimeUnit.MILLISECONDS);
    pool.shutdownNow();
    return ran;
  }

  static void executedBySizedPool(ThreadPoolExecutor pool, CountDownLatch done) {
    pool.execute(() -> done.countDown());
  }

  static boolean executedBySizedPoolClaim(String a, String b) throws InterruptedException {
    var pool = new ThreadPoolExecutor(1, 1, 1, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    var done = new CountDownLatch(1);
    executedBySizedPool(pool, done);
    boolean ran = done.await(WAIT, TimeUnit.MILLISECONDS);
    pool.shutdownNow();
    return ran;
  }

  static void submitted(ExecutorService pool, CountDownLatch done) {
    pool.submit(done::countDown);
  }

  static boolean submittedClaim(String a, String b) throws InterruptedException {
    var pool = Executors.newSingleThreadExecutor();
    var done = new CountDownLatch(1);
    submitted(pool, done);
    boolean ran = done.await(WAIT, TimeUnit.MILLISECONDS);
    pool.shutdownNow();
    return ran;
  }

  static void submittedToCall(ExecutorService pool, AtomicInteger done) {
    pool.submit(() -> done.incrementAndGet());
  }

  static boolean submittedToCallClaim(String a, String b) throws InterruptedException {
    var pool = Executors.newSingleThreadExecutor();
    var done = new AtomicInteger();
    submittedToCall(pool, done);
    pool.shutdown();
    return pool.awaitTermination(WAIT, TimeUnit.MILLISECONDS) && done.get() == 1;
  }

  // Locks let go of: try the lock from another thread, or for a read lock the write lock.

  static int locked(ReentrantLock lock, int value) {
    lock.lock();
    try {
      return value + 1;
    } finally {
      lock.unlock();
    }
  }

  static boolean lockedClaim(String a, String b) throws InterruptedException {
    var lock = new ReentrantLock();
    locked(lock, 1);
    return freeElsewhereClaimed(lock);
  }

  static void lockedThrough(Lock lock, AtomicInteger count) {
    lock.lock();
    count.incrementAndGet();
    lock.unlock();
  }

  static boolean lockedThroughClaim(String a, String b) throws InterruptedException {
    var lock = new ReentrantLock();
    lockedThrough(lock, new AtomicInteger());
    return freeElsewhereClaimed(lock);
  }

  static int readLocked(ReentrantReadWriteLock.ReadLock lock, int value) {
    lock.lock();
    try {
      if (value < 0) {
        throw new IllegalArgumentException();
      }
      return value;
    } finally {
      lock.unlock();
    }
  }

  static boolean readLockedClaim(String a, String b) throws InterruptedException {
    var locks = new ReentrantReadWriteLock();
    readLocked(locks.readLock(), 1);
    return freeElsewhereClaimed(locks.writeLock());
  }

  static void writeLocked(ReentrantReadWriteLock.WriteLock lock, AtomicInteger count) {
    lock.lock();
    try {
      count.incrementAndGet();
    } finally {
      lock.unlock();
    }
  }

  static boolean writeLockedClaim(String a, String b) throws InterruptedException {
    var locks = new ReentrantReadWriteLock();
    writeLocked(locks.writeLock(), new AtomicInteger());
    return freeElsewhereClaimed(locks.writeLock());
  }

  /// Whether another thread can take `lock` now.
  private static boolean freeElsewhereClaimed(Lock lock) throws InterruptedException {
    var free = new AtomicReference<Boolean>(false);
    var other =
        new Thread(
            () -> {
              boolean taken = lock.tryLock();
              free.set(taken);
              if (taken) {
                lock.unlock();
              }
            });
    other.start();
    other.join();
    return free.get();
  }

  // Interrupts: of a thread that waits, which must end; and set again after one was caught.

  static void stop(Thread thread) {
    thread.interrupt();
  }

  static boolean stopClaim(String a, String b) throws InterruptedException {
    var waiting =
        new Thread(
            () -> {
              try {
                Thread.sleep(10 * WAIT);
              } catch (InterruptedException e) {
                // ends
              }
            });
    waiting.setDaemon(true);
    waiting.start();
    stop(waiting);
    waiting.join(WAIT);
    return !waiting.isAlive();
  }

  static boolean pause(int a) {
    try {
      Thread.sleep(a);
      return true;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return false;
    }
  }

  static boolean pauseClaim(String a, String b) {
    Thread.currentThread().interrupt();
    pause(10_000);
    return Thread.interrupted();
  }

  // Daemon threads: a thread from the method is one.

  static Thread daemon(Runnable task) {
    Thread thread = new Thread(task);
    thread.setDaemon(true);
    return thread;
  }

  static boolean daemonClaim(String a, String b) {
    return daemon(() -> {}).isDaemon();
  }

  static ThreadFactory daemons() {
    return runnable -> {
      Thread thread = new Thread(runnable);
      thread.setDaemon(true);
      return thread;
    };
  }

  static boolean daemonsClaim(String a, String b) {
    return daemons().newThread(() -> {}).isDaemon();
  }

  // Handlers: a task on the thread throws, run with start() or execute(), and the handler acts.

  static ThreadFactory handled(Thread.UncaughtExceptionHandler handler) {
    return runnable -> {
      Thread thread = new Thread(runnable);
      thread.setUncaughtExceptionHandler(handler);
      return thread;
    };
  }

  static boolean handledClaim(String a, String b) throws InterruptedException {
    var caught = new CountDownLatch(1);
    var thread =
        handled((t, e) -> caught.countDown())
            .newThread(
                () -> {
                  throw new IllegalStateException();
                });
    thread.start();
    thread.join();
    return caught.await(WAIT, TimeUnit.MILLISECONDS);
  }

  static Thread handledHere(Runnable task, Thread.UncaughtExceptionHandler handler) {
    Thread thread = new Thread(task);
    thread.setUncaughtExceptionHandler(handler);
    return thread;
  }

  static boolean handledHereClaim(String a, String b) throws InterruptedException {
    var caught = new CountDownLatch(1);
    var pool = Executors.newSingleThreadExecutor(task -> handledHere(task, (t, e) -> caught.countDown()));
    pool.execute(
        () -> {
          throw new IllegalStateException();
        });
    boolean handled = caught.await(WAIT, TimeUnit.MILLISECONDS);
    pool.shutdownNow();
    return handled;
  }

  /// What the card says of `submit()`: its task's exception never reaches the handler.
  static boolean handledOnSubmitClaimed() throws InterruptedException {
    var caught = new CountDownLatch(1);
    var pool = Executors.newSingleThreadExecutor(handled((t, e) -> caught.countDown()));
    pool.submit(
        () -> {
          throw new IllegalStateException();
        });
    boolean handled = caught.await(WAIT, TimeUnit.MILLISECONDS);
    pool.shutdownNow();
    return handled;
  }

  static boolean handledOnSubmitClaim(String a, String b) throws InterruptedException {
    return handledOnSubmitClaimed();
  }

  // A pool's wait: give it a task still running when the wait ends, and look at what's done.

  static boolean close(ExecutorService pool) throws InterruptedException {
    pool.shutdown();
    if (!pool.awaitTermination(WAIT / 4, TimeUnit.MILLISECONDS)) {
      pool.shutdownNow();
      return false;
    }
    return true;
  }

  static boolean closeClaim(String a, String b) throws InterruptedException {
    return !close(busyClaimed());
  }

  static String closeFirst(ExecutorService pool) throws InterruptedException {
    pool.shutdown();
    if (pool.awaitTermination(WAIT / 4, TimeUnit.MILLISECONDS)) {
      return "done";
    }
    pool.shutdownNow();
    return "stopped";
  }

  static boolean closeFirstClaim(String a, String b) throws InterruptedException {
    return closeFirst(busyClaimed()).equals("stopped");
  }

  static int closeAfterWaits(ExecutorService pool) throws InterruptedException {
    pool.shutdown();
    int waits = 1;
    while (!pool.awaitTermination(WAIT / 4, TimeUnit.MILLISECONDS)) {
      waits++;
    }
    return waits;
  }

  static boolean closeAfterWaitsClaim(String a, String b) throws InterruptedException {
    return closeAfterWaits(busyClaimed()) > 1;
  }

  /// A pool whose one task is still running when a wait of a quarter of [#WAIT] ends.
  private static ExecutorService busyClaimed() {
    var pool = Executors.newSingleThreadExecutor();
    pool.execute(
        () -> {
          try {
            Thread.sleep(WAIT);
          } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });
    return pool;
  }
}
