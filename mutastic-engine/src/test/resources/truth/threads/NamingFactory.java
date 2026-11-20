package threads;

import java.util.concurrent.ThreadFactory;

/// A factory whose job is names, as Commons Lang's `BasicThreadFactory` is: its tests check them.
class NamingFactory implements ThreadFactory {
  @Override
  public Thread newThread(Runnable task) {
    var thread = new Thread(task);
    thread.setName("named-worker");
    return thread;
  }
}
