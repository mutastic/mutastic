package com.example.arid;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class WorkersTest {
  @Test
  void namesItsThreadForTheThreadDump() {
    assertEquals("report-worker", Workers.background(() -> {}).getName());
  }

  @Test
  void runsInTheBackground() {
    assertEquals(Thread.MIN_PRIORITY, Workers.background(() -> {}).getPriority());
  }

  @Test
  void letsIdleThreadsGoAfterHalfAMinute() {
    var pool = Workers.pool();
    assertEquals(30, pool.getKeepAliveTime(TimeUnit.SECONDS));
    pool.shutdown();
  }

  @Test
  void hasTheStackForDeepWork() throws Exception {
    var depth = new AtomicInteger();
    var thread = Workers.deep(() -> depth.set(down(200_000)));
    thread.start();
    thread.join();
    assertEquals(200_000, depth.get());
  }

  private static int down(int n) {
    return n == 0 ? 0 : 1 + down(n - 1);
  }
}
