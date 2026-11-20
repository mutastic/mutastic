package threads;

/// Code that reads a thread's name for more than a log line: names matter here.
class ReadsNames {
  static Thread named(Runnable task) {
    var thread = new Thread(task);
    thread.setName("background-task");
    return thread;
  }

  static boolean isWorker() {
    return Thread.currentThread().getName().startsWith("background");
  }
}
