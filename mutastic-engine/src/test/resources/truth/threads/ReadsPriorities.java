package threads;

/// Code that reads a thread's priority: priorities matter here.
class ReadsPriorities {
  static Thread urgent(Runnable task) {
    var thread = new Thread(task);
    thread.setPriority(Thread.MAX_PRIORITY);
    return thread;
  }

  static boolean isUrgent(Thread thread) {
    return thread.getPriority() == Thread.MAX_PRIORITY;
  }
}
