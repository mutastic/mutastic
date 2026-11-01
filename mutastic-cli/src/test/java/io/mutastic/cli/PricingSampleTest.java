package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// End to end on `samples/pricing`, a JDK 17 Gradle project: Gradle reads the setup, the engine
/// runs on 25 and the tests on 17, and the loop an agent follows closes a gap.
class PricingSampleTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");

  @TempDir
  Path work;

  @Test
  void findsTheMissedEdgeAndVerifiesTheFix() throws Exception {
    var project = copy(SAMPLE, work.resolve("pricing"));

    var run = mutastic("run", project.toString(), "--format=jsonl");

    assertThat(run.exit()).as(run.err()).isEqualTo(Main.PASSED);
    assertThat(run.out()).contains("\"reason\":\"run-finished\",\"jdk\":17");
    assertThat(run.out()).contains("\"Killed\":6,\"Survived\":1,\"Timeout\":0,\"NoCoverage\":0,\"Ignored\":2");
    assertThat(run.out()).contains("\"statusReason\":\"equivalent: already returns the default\"");
    var missed = run.out().lines().filter(line -> line.contains("\"status\":\"Survived\"")).findFirst().orElseThrow();
    assertThat(missed).contains("\"line\":17").contains("\"original\":\">=\",\"replacement\":\">\"");
    var id = missed.replaceAll(".*\"id\":\"(m[0-9a-f]{8})\".*", "$1");

    assertThat(mutastic("verify", id, project.toString()).exit()).isEqualTo(Main.FAILED);

    // What the advice asks for: a test at the edge, and no production change.
    var test = project.resolve("src/test/java/com/example/pricing/PricingTest.java");
    Files.writeString(test, Files.readString(test).replaceFirst("\\}\\s*$", """

          @Test
          void anOrderOfExactlyOneHundredGetsTheDiscount() {
            assertEquals(10, new Pricing().discount(100));
          }
        }
        """));

    var verified = mutastic("verify", id, project.toString());
    assertThat(verified.exit()).isEqualTo(Main.PASSED);
    assertThat(verified.out()).contains(id + " is caught by com.example.pricing.PricingTest#anOrderOfExactlyOneHundredGetsTheDiscount");
  }

  @Test
  void agentTextLeadsWithTheVerdict() throws Exception {
    var project = copy(SAMPLE, work.resolve("pricing"));

    var run = mutastic("run", project.toString());

    assertThat(run.out().lines().findFirst().orElseThrow())
        .isEqualTo("mutastic: PASS · score 85.7% (threshold 80) · 1 missed, 0 not covered, 6 caught of 7 mutants (2 more skipped)");
    assertThat(run.out()).contains("   - if (total >= 100) {", "   + if (total > 100) {");
  }

  private record Result(int exit, String out, String err) {}

  private static Result mutastic(String... args) {
    var out = new ByteArrayOutputStream();
    var err = new ByteArrayOutputStream();
    int exit = Main.run(args, new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));
    return new Result(exit, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
  }

  /// The sample without its build output, so each test builds its own.
  private static Path copy(Path from, Path to) throws IOException {
    try (Stream<Path> files = Files.walk(from)) {
      files.filter(file -> {
        var top = from.relativize(file).getName(0).toString();
        return !top.equals("build") && !top.equals(".gradle");
      }).forEach(file -> {
        try {
          var target = to.resolve(from.relativize(file).toString());
          if (Files.isDirectory(file)) {
            Files.createDirectories(target);
          } else {
            Files.copy(file, target);
            target.toFile().setExecutable(file.toFile().canExecute());
          }
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      });
    }
    return to;
  }
}
