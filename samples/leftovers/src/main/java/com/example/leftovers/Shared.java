package com.example.leftovers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/// Two methods that write the same file in a directory and delete it again. A mutant that removes
/// the first one's delete leaves the file there; a test of the second, on a mutant that removes its
/// write, then reads the file the first left and passes, unless each mutant's tests start without
/// it.
public class Shared {
  /// Writes `text` to `shared.txt` in `dir`, and deletes it again: how long it was.
  public static int stash(Path dir, String text) throws IOException {
    Path file = dir.resolve("shared.txt");
    Files.writeString(file, text);
    Files.delete(file);
    return text.length();
  }

  /// Writes `text` to `shared.txt` in `dir`, and measures the file: its size in bytes.
  public static long measure(Path dir, String text) throws IOException {
    Path file = dir.resolve("shared.txt");
    Files.writeString(file, text);
    long size = Files.size(file);
    Files.delete(file);
    return size;
  }
}
