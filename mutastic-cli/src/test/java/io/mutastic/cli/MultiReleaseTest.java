package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `samples/multi-release`: Java 8 code, and a Java 11 class compiled into `META-INF/versions/11`,
/// which Surefire's `additionalClasspathElements` puts on the tests' classpath, as jsoup's build
/// does (corpus A11). The worker never loaded it, so every test that reached it failed without any
/// mutant, and mutastic made the class's mutants from the wrong file.
class MultiReleaseTest {
  private static final Path SAMPLE =
      Path.of(System.getProperty("mutastic.samples"), "multi-release");

  @TempDir Path work;

  @Test
  void theJava11ClassLoadsAndItsMutantsRun() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("multi-release"));

    var run =
        Samples.mutastic(
            Map.of("MUTASTIC_FORMAT", "jsonl"), "run", project.toString(), "--report=none");

    assertThat(run.exit()).as(run.err()).isZero();
    assertThat(run.out()).doesNotContain("\"code\":\"red-baseline\"");
    var builtin =
        run.out()
            .lines()
            .filter(line -> line.contains("\"reason\":\"mutant-finished\""))
            .filter(line -> line.contains("\"class\":\"com.example.repeat.Builtin"))
            .toList();
    assertThat(builtin)
        .isNotEmpty()
        .allSatisfy(
            line ->
                assertThat(line)
                    .contains("\"status\":\"Killed\"")
                    .contains("\"file\":\"src/main/java11/com/example/repeat/Builtin.java\""));
    // The nested class is the versioned copy's too.
    assertThat(builtin).anyMatch(line -> line.contains("com.example.repeat.Builtin$Word"));
  }
}
