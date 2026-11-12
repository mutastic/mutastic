package io.mutastic.cli;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

/// One run at a time in a module's work directory. Two runs on one module at once (an agent's gate
/// and a person's run) would write each other's setup, staged classes, coverage and records in the
/// middle of a run, so a mutant could be judged by the other run's: a false catch. The first holds
/// `lock` in the directory until it ends; a second waits, and says so.
///
/// A run in this JVM that's already holding the directory doesn't wait on itself: a JVM can hold a
/// file's lock only once, and a gate or a test runs more than one run in the same JVM, one inside
/// or after another. So the lock is the JVM's, counted, and let go when the last run holding it
/// ends.
final class WorkLock implements AutoCloseable {
  private static final Map<Path, Held> HELD = new HashMap<>();

  private final Path directory;

  private WorkLock(Path directory) {
    this.directory = directory;
  }

  /// The lock on `directory`, waiting for another process's run there to end, and saying so on
  /// `err`.
  static WorkLock hold(Path directory, PrintStream err) {
    var key = directory.toAbsolutePath().normalize();
    synchronized (HELD) {
      var held = HELD.get(key);
      if (held != null) {
        held.count++;
      } else {
        HELD.put(key, new Held(take(key, err)));
      }
    }
    return new WorkLock(key);
  }

  /// This JVM's lock on the directory's `lock` file, once another process lets it go.
  private static FileLock take(Path directory, PrintStream err) {
    var file = directory.resolve("lock");
    try {
      Files.createDirectories(directory);
      var channel = FileChannel.open(file, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
      var lock = channel.tryLock();
      if (lock == null) {
        err.println(
            "mutastic: waiting for another mutastic run on this module to finish (" + file + ")");
        lock = channel.lock();
      }
      return lock;
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  @Override
  public void close() {
    synchronized (HELD) {
      var held = HELD.get(directory);
      if (held == null || --held.count > 0) {
        return;
      }
      HELD.remove(directory);
      try {
        held.lock.release();
        held.lock.channel().close();
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
  }

  /// The JVM's lock on one directory, and how many of its runs hold it.
  private static final class Held {
    private final FileLock lock;
    private int count = 1;

    Held(FileLock lock) {
      this.lock = lock;
    }
  }
}
