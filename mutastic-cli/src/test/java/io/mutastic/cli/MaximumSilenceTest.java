package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A run is never silent: on a project whose one test sleeps through more than one heartbeat, with
/// stderr a pipe and the heartbeat a second, no two lines are more than the heartbeat and a second
/// apart, and every phase says something. See `PROGRESS.md`.
class MaximumSilenceTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");

  private static final Duration HEARTBEAT = Duration.ofSeconds(1);

  /// Longer than a heartbeat, so a phase with nothing new to say must still say something.
  private static final int SLEEP_MILLIS = 3000;

  @TempDir Path work;

  /// A line of stderr, and when it ended.
  private record Line(long nanos, String text) {}

  /// Each line written to it, as it ends, with the time.
  private static final class Timestamped extends OutputStream {
    private final List<Line> lines = new ArrayList<>();
    private final java.io.ByteArrayOutputStream current = new java.io.ByteArrayOutputStream();

    @Override
    public synchronized void write(int b) {
      if (b == '\n') {
        lines.add(new Line(System.nanoTime(), current.toString(StandardCharsets.UTF_8)));
        current.reset();
      } else {
        current.write(b);
      }
    }

    synchronized List<Line> lines() {
      return List.copyOf(lines);
    }
  }

  @Test
  void everyPhaseSaysSomethingAndNoSilenceOutlastsTheHeartbeat() throws IOException {
    var project = slow(Samples.copy(SAMPLE, work.resolve("slow")));
    var err = new Timestamped();
    long started = System.nanoTime();

    int exit =
        Main.run(
            new String[] {"run", project.toString(), "--workers=2"},
            new PrintStream(OutputStream.nullOutputStream(), true, StandardCharsets.UTF_8),
            new PrintStream(err, true, StandardCharsets.UTF_8),
            new Terminal(false, Map.of("MUTASTIC_HEARTBEAT", "1")));
    long ended = System.nanoTime();

    var lines = err.lines();
    var said = lines.stream().map(Line::text).toList();
    assertThat(exit).as(String.join("\n", said)).isIn(Main.PASSED, Main.FAILED);
    var times = new ArrayList<Long>(List.of(started));
    lines.forEach(line -> times.add(line.nanos()));
    times.add(ended);
    var longest = Duration.ZERO;
    for (int at = 1; at < times.size(); at++) {
      var gap = Duration.ofNanos(times.get(at) - times.get(at - 1));
      longest = gap.compareTo(longest) > 0 ? gap : longest;
    }
    assertThat(longest).as(String.join("\n", said)).isLessThan(HEARTBEAT.plusSeconds(1));
    for (var phase : List.of("building", "coverage", "mutants", "checking")) {
      assertThat(said).as(phase).anyMatch(line -> line.startsWith(phase + " "));
    }
    assertThat(said)
        .anyMatch(line -> line.startsWith("mutastic ") && line.contains("reading the Gradle build"))
        .anyMatch(line -> line.startsWith("built     "))
        .anyMatch(line -> line.startsWith("ran       "))
        .anyMatch(line -> line.startsWith("checked   "));
  }

  /// The pricing sample with its code and tests replaced by one method and one slow test, which
  /// checks a value either side of the method's edge but never the edge: one mutant is missed.
  private static Path slow(Path project) throws IOException {
    var main = project.resolve("src/main/java/com/example/pricing");
    var test = project.resolve("src/test/java/com/example/pricing");
    Files.delete(main.resolve("Pricing.java"));
    Files.delete(test.resolve("PricingTest.java"));
    Files.writeString(
        main.resolve("Slow.java"),
        """
        package com.example.pricing;

        public final class Slow {
          private Slow() {}

          public static boolean over(int amount) {
            return amount > 100;
          }
        }
        """);
    Files.writeString(
        test.resolve("SlowTest.java"),
        """
        package com.example.pricing;

        import static org.junit.jupiter.api.Assertions.assertFalse;
        import static org.junit.jupiter.api.Assertions.assertTrue;

        import org.junit.jupiter.api.Test;

        class SlowTest {
          @Test
          void overAHundred() throws InterruptedException {
            Thread.sleep(%d);
            assertTrue(Slow.over(150));
            assertFalse(Slow.over(50));
          }
        }
        """
            .formatted(SLEEP_MILLIS));
    return project;
  }
}
