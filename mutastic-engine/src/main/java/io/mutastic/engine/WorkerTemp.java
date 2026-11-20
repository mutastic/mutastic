package io.mutastic.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// A worker's own temporary directory, its `java.io.tmpdir`. A test's temporary files are deleted
/// as the test ends, and a worker killed mid-test (by a mutant that never finishes) ends no test:
/// whatever the test made would stay in the user's temporary directory for good. So each worker
/// gets a directory of its own, deleted with everything in it once the worker has gone, however it
/// went.
record WorkerTemp(Path directory) {
  static WorkerTemp create() {
    try {
      return new WorkerTemp(Files.createTempDirectory("mutastic-worker-"));
    } catch (IOException e) {
      throw new UncheckedIOException("couldn't make a temporary directory for the test worker", e);
    }
  }

  /// The JVM argument that makes it the worker's temporary directory. A project's own setting,
  /// later on the command line, wins.
  String jvmArg() {
    return "-Djava.io.tmpdir=" + directory;
  }

  /// What's in the directory now, its entries at the top: none if it can't be read.
  Set<Path> contents() {
    try (Stream<Path> entries = Files.list(directory)) {
      return entries.collect(Collectors.toSet());
    } catch (IOException | UncheckedIOException e) {
      return Set.of();
    }
  }

  /// Deletes what's in the directory beyond `kept`, everything in it included, as far as it can. A
  /// mutant's tests start with the directory as the last tests left it, and a file one mutant's
  /// tests leave there (a mutant that removes the delete) would still be there for the next
  /// mutant's: a test that reads it back could pass on a mutant that never wrote it. So what each
  /// run adds goes once it has answered. What was there before stays: a class the coverage pass
  /// loaded can hold on to a file or a directory it made there, as an embedded server does, and it
  /// would be gone for every mutant after.
  void deleteAllBut(Set<Path> kept) {
    for (var entry : contents()) {
      if (!kept.contains(entry)) {
        deleteTree(entry);
      }
    }
  }

  /// Deletes the directory and everything in it, as far as it can: a file that can't be deleted
  /// leaves its directories behind, and nothing else.
  void delete() {
    deleteTree(directory);
  }

  private static void deleteTree(Path top) {
    try (Stream<Path> files = Files.walk(top)) {
      files.sorted(Comparator.reverseOrder()).forEach(WorkerTemp::deleteIfAble);
    } catch (IOException | UncheckedIOException e) {
      // already gone, or unreadable: nothing more to do
    }
  }

  private static void deleteIfAble(Path file) {
    try {
      Files.deleteIfExists(file);
    } catch (IOException e) {
      // in use or not ours to delete: leave it
    }
  }
}
