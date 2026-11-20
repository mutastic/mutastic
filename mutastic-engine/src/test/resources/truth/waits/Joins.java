package waits;

import java.util.concurrent.atomic.AtomicInteger;

/// A thread's result read straight after a `join` with a time limit: without the `join`, the code
/// reads it before the thread is done.
class Joins {
  static int joined(int a, int b) throws InterruptedException {
    var result = new AtomicInteger();
    var worker =
        new Thread(
            () -> {
              try {
                Thread.sleep(100);
              } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
              }
              result.set(a * 2);
            });
    worker.start();
    worker.join(5_000);
    return result.get();
  }
}
