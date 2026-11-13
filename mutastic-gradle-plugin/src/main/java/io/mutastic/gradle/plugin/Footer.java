package io.mutastic.gradle.plugin;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import org.gradle.api.model.ObjectFactory;

/// The run in Gradle's own progress line, `> :mutasticRun > mutants 2871/5525 · 52% · ✗ 41 missed ·
/// ~21m left`, from the live events the command writes to a file as it runs: read as the file
/// grows, each line that says something shown. Gradle's progress line is internal API, so where it
/// isn't there nothing is shown, and the command's heartbeat lines on stderr say how it's going.
final class Footer implements Shown {
  /// The progress line: what it says, until closed.
  interface Line extends AutoCloseable {
    void say(String status);

    @Override
    void close();
  }

  /// How often the file is read for new events.
  private static final Duration POLL = Duration.ofMillis(200);

  private final Line line;
  private final Path events;
  private final Thread reader;
  private volatile boolean stopped;
  private long read;
  private final StringBuilder partial = new StringBuilder();

  private Footer(Line line, Path events) {
    this.line = line;
    this.events = events;
    this.reader = new Thread(this::follow, "mutastic-footer");
    reader.setDaemon(true);
    reader.start();
  }

  /// Gradle's progress line following `events`, if this Gradle has one to give; else nothing.
  static Shown show(ObjectFactory objects, Path events) {
    try {
      return new Footer(objects.newInstance(ProgressLoggers.class).start("mutastic"), events);
    } catch (RuntimeException | LinkageError e) {
      return () -> {}; // no progress line here: the heartbeat's lines say how it's going
    }
  }

  private void follow() {
    while (!stopped) {
      readNew();
      try {
        Thread.sleep(POLL.toMillis());
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return;
      }
    }
  }

  /// The lines written since the last read, each shown if it says something.
  private synchronized void readNew() {
    try (var file = new RandomAccessFile(events.toFile(), "r")) {
      var bytes = new byte[(int) Math.max(0, file.length() - read)];
      file.seek(read);
      file.readFully(bytes);
      read += bytes.length;
      partial.append(new String(bytes, StandardCharsets.UTF_8));
    } catch (IOException e) {
      return; // not written yet
    }
    for (int end = partial.indexOf("\n"); end >= 0; end = partial.indexOf("\n")) {
      FooterText.of(partial.substring(0, end)).ifPresent(line::say);
      partial.delete(0, end + 1);
    }
  }

  /// Stops following, with what's left read, and takes the line away.
  @Override
  public void close() {
    stopped = true;
    reader.interrupt();
    try {
      reader.join(POLL.multipliedBy(5).toMillis());
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    readNew();
    line.close();
  }
}
