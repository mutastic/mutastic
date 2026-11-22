package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A test that calls `main` in the tests' own JVM, where a mutant can make `main` call
/// `System.exit`. The worker goes with it, but that isn't a hang: it's what the test saw the mutant
/// do. Status 2 fails a build's test run, so the test that was running caught it, by the exit;
/// status 0 passes Gradle's, so it's no catch, and the tests after it decide. A loop that no longer
/// ends still times out.
class ExitingMainTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final String PRINTS = "com.example.pricing.CliTest#printsTheDiscount";

  /// `args.length == 0` always true: `main` exits 2 under `printsTheDiscount`.
  private static final String EXITS_2 = "mb4afe892";

  @TempDir Path work;
  private Path project;

  @BeforeEach
  void writeMainAndItsTest() throws IOException {
    project = Samples.copy(SAMPLE, work.resolve("pricing"));
    Files.writeString(
        project.resolve("src/main/java/com/example/pricing/Cli.java"),
        """
        package com.example.pricing;

        /// Prints the discount on a total. With no total it says how to use it and exits 2; with
        /// `--help` it says so and exits 0.
        public final class Cli {
          private Cli() {}

          public static void main(String[] args) {
            if (args.length == 0) {
              System.err.println("usage: cli <total>");
              System.exit(2);
            }
            if (args[0].equals("--help")) {
              System.out.println("prints the discount on a total");
              System.exit(0);
            }
            System.out.println(new Pricing().discount(Integer.parseInt(args[0])));
          }

          /// How many digits `total` has.
          static int digits(int total) {
            int count = 1;
            while (total >= 10) {
              total /= 10;
              count++;
            }
            return count;
          }
        }
        """);
    Files.writeString(
        project.resolve("src/test/java/com/example/pricing/CliTest.java"),
        """
        package com.example.pricing;

        import static org.junit.jupiter.api.Assertions.assertEquals;

        import java.io.ByteArrayOutputStream;
        import java.io.PrintStream;
        import java.nio.charset.StandardCharsets;
        import org.junit.jupiter.api.Test;

        class CliTest {
          @Test
          void printsTheDiscount() {
            var out = new ByteArrayOutputStream();
            var was = System.out;
            System.setOut(new PrintStream(out, true, StandardCharsets.UTF_8));
            try {
              Cli.main(new String[] {"120"});
            } finally {
              System.setOut(was);
            }
            assertEquals("12", out.toString(StandardCharsets.UTF_8).strip());
          }

          @Test
          void countsDigits() {
            assertEquals(3, Cli.digits(120));
          }
        }
        """);
  }

  @Test
  void anExitIsCaughtByTheTestThatRanItUnlessItsStatusIsZero() {
    var run =
        Samples.mutastic(
            "run", project.toString(), "--format=jsonl", "--report=none", "--threshold=0");

    var lines = run.out().lines().toList();
    assertThat(finished(lines, "`args.length == 0` is always true"))
        .contains(
            "\"id\":\"" + EXITS_2 + "\"",
            "\"status\":\"Killed\"",
            "\"caughtBy\":\"" + PRINTS + "\"",
            "\"caughtWith\":\"exit: 2\"",
            "\"failureOrigin\":\"exit\"");
    assertThat(finished(lines, "`equals(\\\"--help\\\")` is always true"))
        .contains("\"status\":\"Survived\"")
        .doesNotContain("caughtBy");
    assertThat(finished(lines, "`total >= 10` is always true")).contains("\"status\":\"Timeout\"");
  }

  /// `explain` runs it again, and says how it was caught.
  @Test
  void explainSaysTheJvmExitedAndWithWhatStatus() {
    var explained = Samples.mutastic("explain", EXITS_2, project.toString());

    assertThat(explained.out())
        .contains(
            "[✓ CAUGHT] caught by CliTest#printsTheDiscount, by the JVM exiting with status 2");
  }

  private static String finished(List<String> lines, String description) {
    return lines.stream()
        .filter(line -> line.contains("\"reason\":\"mutant-finished\""))
        .filter(line -> line.contains("\"description\":\"" + description + "\""))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no mutant-finished for " + description));
  }
}
