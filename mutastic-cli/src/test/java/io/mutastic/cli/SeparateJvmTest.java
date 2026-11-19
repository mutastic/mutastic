package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A test that runs the project's `main` as a person would, in a JVM of its own started on
/// `java.class.path`. That JVM gets the classes as they're built, not the mutant, and the coverage
/// pass can't see it: the run says so, and what it says is what happens. The test passes, as it
/// does in the build. A mutant its check would catch is missed, as no mutant reaches that JVM. And
/// `main`, which only that JVM runs, reads as reached by no test.
class SeparateJvmTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");

  @TempDir Path work;

  @Test
  void aTestThatRunsMainInAJvmOfItsOwnIsSaidToReachNoMutant() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    writeMainAndItsTests(project, true);

    var run =
        Samples.mutastic(
            "run", project.toString(), "--format=brief", "--report=none", "--threshold=0");

    assertThat(run.err())
        .contains(
            "note      ReceiptTest runs com.example.pricing.Receipt in a separate JVM, and mutants"
                + " don't reach that JVM. So a test there can't catch a mutant in Receipt, and code"
                + " only it runs reads as reached by no test. To check Receipt, call what its main"
                + " calls from a test in the tests' own JVM")
        .doesNotContain("fail without any mutant");
    assertThat(run.out())
        .contains("[✗ MISSED] no test checks what line() returns here")
        .contains("src/main/java/com/example/pricing/Receipt.java:13")
        .containsPattern("Receipt.java:8 main\\(\\) \\(\\d mutants?\\)");
  }

  /// With only that test, the coverage pass sees none of the code: the run stops, and says why.
  @Test
  void onlyATestThatRunsMainInAJvmOfItsOwnIsWhyNothingIsCovered() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    delete(project.resolve("src/test"));
    writeMainAndItsTests(project, false);

    var run = Samples.mutastic("run", project.toString(), "--format=brief", "--report=none");

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err())
        .contains("1 test ran and passed in pricing, but the coverage pass recorded no line")
        .contains("read as reached by no test. ReceiptTest runs com.example.pricing.Receipt in a")
        .doesNotContain("Most likely");
  }

  private static void delete(Path directory) throws IOException {
    try (var walk = Files.walk(directory)) {
      for (var each : walk.sorted(java.util.Comparator.reverseOrder()).toList()) {
        Files.delete(each);
      }
    }
  }

  /// `Receipt.main` prints `line`'s text, which `ReceiptTest` checks in a JVM of its own. With
  /// `inProcess`, another test calls `line` in the tests' own JVM, but doesn't check its text.
  private static void writeMainAndItsTests(Path project, boolean inProcess) throws IOException {
    var code = project.resolve("src/main/java/com/example/pricing/Receipt.java");
    Files.writeString(
        code,
        """
        package com.example.pricing;

        /// Prints a receipt's line for a total.
        public final class Receipt {
          private Receipt() {}

          public static void main(String[] args) {
            int total = args.length > 0 ? Integer.parseInt(args[0]) : 0;
            System.out.println(line(total));
          }

          static String line(int total) {
            return "total " + total;
          }
        }
        """);
    var tests = project.resolve("src/test/java/com/example/pricing/ReceiptTest.java");
    Files.createDirectories(tests.getParent());
    Files.writeString(
        tests,
        """
        package com.example.pricing;

        import static org.junit.jupiter.api.Assertions.assertEquals;
        import static org.junit.jupiter.api.Assertions.assertNotNull;

        import java.nio.charset.StandardCharsets;
        import java.nio.file.Path;
        import java.util.List;
        import org.junit.jupiter.api.Test;

        class ReceiptTest {
          @Test
          void printsTheTotal() throws Exception {
            var java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
            var classPath = System.getProperty("java.class.path");
            var process =
                new ProcessBuilder(List.of(java, "-cp", classPath, Receipt.class.getName(), "12"))
                    .redirectErrorStream(true)
                    .start();
            var out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.waitFor());
            assertEquals("total 12", out.strip());
          }

        %s}
        """
            .formatted(
                inProcess
                    ? """
                      @Test
                      void hasALine() {
                        assertNotNull(Receipt.line(1));
                      }
                    """
                    : ""));
  }
}
