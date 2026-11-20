package threads;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/// A pool whose core size changes after it's made: past its largest size, that throws.
class Resizes {
  static ThreadPoolExecutor unbounded() {
    return new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
  }

  static void grow(ThreadPoolExecutor pool) {
    pool.setCorePoolSize(4);
  }
}
