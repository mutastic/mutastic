package io.mutastic.cli;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/// Another process's run, for [WorkLockTest]: holds a work directory's `lock`, says `held`, and
/// lets go when its standard input closes.
final class LockHolder {
  private LockHolder() {}

  public static void main(String[] args) throws IOException {
    var directory = Path.of(args[0]);
    Files.createDirectories(directory);
    try (var channel =
            FileChannel.open(
                directory.resolve("lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
        var lock = channel.lock()) {
      System.out.println("held " + lock.isValid());
      System.out.flush();
      while (System.in.read() >= 0) {
        // until the test closes it
      }
    }
  }
}
