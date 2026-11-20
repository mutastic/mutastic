package com.example.arid;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** The threads a report server works on: named for its thread dumps, and kept in the background. */
public final class Workers {
  private Workers() {}

  public static Thread background(Runnable work) {
    var thread = new Thread(work, "report-worker");
    thread.setPriority(Thread.MIN_PRIORITY);
    return thread;
  }

  public static Thread deep(Runnable work) {
    return new Thread(null, work, "deep-worker", 64L * 1024 * 1024);
  }

  public static ThreadPoolExecutor pool() {
    return new ThreadPoolExecutor(1, 4, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>());
  }
}
