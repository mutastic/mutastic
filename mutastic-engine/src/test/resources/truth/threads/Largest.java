package threads;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/// A pool whose queue has no limit, given as many tasks as `a` says at once, each held until all
/// are in: how many threads it ever has is the same whatever its largest size, from its core size
/// up.
class Largest {
  static int largest(int a, int b) throws InterruptedException {
    var pool = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
    var held = new CountDownLatch(1);
    for (int task = 0; task < Math.floorMod(a, 6); task++) {
      pool.execute(() -> awaitQuietly(held));
    }
    held.countDown();
    pool.shutdown();
    pool.awaitTermination(10, TimeUnit.SECONDS);
    return pool.getLargestPoolSize() * 100 + pool.getQueue().size();
  }

  private static void awaitQuietly(CountDownLatch latch) {
    try {
      latch.await();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }
}
