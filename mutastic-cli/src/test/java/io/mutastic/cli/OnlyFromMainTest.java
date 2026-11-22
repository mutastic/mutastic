package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// Code only `main` runs, which no test calls: the not-covered line says it's reached only from
/// `main`, and to move what `main` does into `run(args, out)`, test that, and leave `main` a
/// one-line call. What it says is checked by doing it: the same code moved into `run`, with a test
/// of `run`, has every mutant caught, and the one line left in `main` gets no such advice.
///
/// The first `main` exits with what's to pay, where it once printed it: a value that only feeds a
/// print is arid, so a `main` that only printed had one line of mutants and nothing to move.
class OnlyFromMainTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final String RECEIPT = "src/main/java/com/example/pricing/Receipt.java";
  private static final String ADVICE =
      "reached only from `main()`: move what it does into `run(args, out)`, which returns the exit"
          + " code, test `run`, and leave `main()` a one-line call to it";

  @TempDir Path work;

  @Test
  void codeOnlyMainRunsSaysSoAndWhatToDo() throws IOException {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    write(
        project.resolve(RECEIPT),
        """
        package com.example.pricing;

        /// Exits with what's to pay on a total, with the discount taken off.
        public final class Receipt {
          private Receipt() {}

          public static void main(String[] args) {
            int total = args.length > 0 ? Integer.parseInt(args[0]) : 0;
            System.exit(total - new Pricing().discount(total));
          }
        }
        """);

    var agent = run(project, "--format=agent");
    var human = run(project, "--color=never");

    assertThat(agent.out())
        .contains(
            "- " + RECEIPT + ":8-9 main() (4 mutants)\n  " + ADVICE + "\n  or propose (line 8)");
    assertThat(human.out())
        .contains("○ " + RECEIPT + ":8-9 main() (4 mutants)\n     " + ADVICE.replace("`", ""));
  }

  /// The advice, taken: `run` holds what `main` did, a test checks it, and `main` calls it. Its
  /// `println` is skipped as only writing output.
  @Test
  void theAdviceTakenCatchesWhatMainDidAndLeavesOneLine() throws IOException {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    write(
        project.resolve(RECEIPT),
        """
        package com.example.pricing;

        import java.io.PrintStream;

        /// Prints what's to pay on a total, with the discount taken off.
        public final class Receipt {
          private Receipt() {}

          public static void main(String[] args) {
            System.exit(run(args, System.out));
          }

          static int run(String[] args, PrintStream out) {
            int total = args.length > 0 ? Integer.parseInt(args[0]) : 0;
            out.println("total " + (total - new Pricing().discount(total)));
            return 0;
          }
        }
        """);
    write(
        project.resolve("src/test/java/com/example/pricing/ReceiptTest.java"),
        """
        package com.example.pricing;

        import static org.junit.jupiter.api.Assertions.assertEquals;

        import java.io.ByteArrayOutputStream;
        import java.io.PrintStream;
        import java.nio.charset.StandardCharsets;
        import org.junit.jupiter.api.Test;

        class ReceiptTest {
          @Test
          void takesTheDiscountOff() {
            assertEquals("total 108", printed("120"));
          }

          @Test
          void noTotalIsZero() {
            assertEquals("total 0", printed());
          }

          private static String printed(String... args) {
            var out = new ByteArrayOutputStream();
            assertEquals(0, Receipt.run(args, new PrintStream(out, true, StandardCharsets.UTF_8)));
            return out.toString(StandardCharsets.UTF_8).strip();
          }
        }
        """);

    var events = run(project, "--format=jsonl").out().lines().toList();
    var agent = run(project, "--format=agent");

    var inRun =
        events.stream()
            .filter(line -> line.contains("\"reason\":\"mutant-finished\""))
            .filter(line -> line.contains("\"method\":\"run("))
            .filter(line -> !line.contains("\"status\":\"Ignored\""))
            .toList();
    assertThat(inRun).isNotEmpty().allMatch(line -> line.contains("\"status\":\"Killed\""));
    assertThat(agent.out())
        .contains(RECEIPT + ":10 main() (1 mutant)")
        .doesNotContain("reached only from");
  }

  private static Samples.Result run(Path project, String format) {
    return Samples.mutastic("run", project.toString(), format, "--report=none", "--threshold=0");
  }

  private static void write(Path file, String text) throws IOException {
    Files.createDirectories(file.getParent());
    Files.writeString(file, text);
  }
}
