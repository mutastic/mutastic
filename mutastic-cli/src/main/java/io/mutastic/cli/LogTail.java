package io.mutastic.cli;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Optional;

/// The latest line a build tool has written to its log, read from the log's end as it grows.
final class LogTail {
  /// How much of the log's end is read: more than any one line progress shows.
  private static final int END = 4096;

  /// A terminal's escape sequence, colour say, or any other control character.
  private static final java.util.regex.Pattern CONTROL =
      java.util.regex.Pattern.compile("\u001b\\[[0-9;]*[A-Za-z]|\\p{Cntrl}");

  private final Path log;

  LogTail(Path log) {
    this.log = log;
  }

  /// The last line with something on it, without control characters; empty before the tool has
  /// written one, or when the log can't be read.
  Optional<String> latest() {
    try (var channel = FileChannel.open(log, StandardOpenOption.READ)) {
      long size = channel.size();
      var end = ByteBuffer.allocate((int) Math.min(END, size));
      channel.read(end, size - end.capacity());
      return lastLine(new String(end.array(), StandardCharsets.UTF_8));
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /// The last line of `text` that isn't blank once its control characters are gone.
  static Optional<String> lastLine(String text) {
    return text.lines()
        .map(line -> CONTROL.matcher(line).replaceAll("").strip())
        .filter(line -> !line.isEmpty())
        .reduce((first, second) -> second);
  }
}
