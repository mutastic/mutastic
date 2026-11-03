package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// The output the docs show is what mutastic prints: each file in docs/src/data/output is a
/// command's output on the pricing sample, in colour, compared here with a fresh run. A change to
/// what mutastic prints fails this until the docs' copy is written again.
///
/// To write it: `./gradlew :mutastic-cli:exportDocsOutput`.
class DocsOutputTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final Path OUTPUT = Path.of(System.getProperty("mutastic.docsOutput"));
  private static final boolean WRITE = Boolean.getBoolean("mutastic.docsOutput.write");
  private static final String ID = "m855c8e3c";

  @TempDir Path work;

  @Test
  void explainAfterARun() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    Samples.mutastic("run", project.toString(), "--format=brief", "--report=none");

    var explain = Samples.mutastic(Map.of("FORCE_COLOR", "1"), "explain", ID, project.toString());

    assertThat(explain.exit()).isEqualTo(Main.PASSED);
    shown("explain.ansi", explain.out().replace(project.toString(), "samples/pricing"));
  }

  /// As the docs show it, from the repository's root, where `samples/pricing` is typed.
  private static void shown(String name, String output) throws Exception {
    var file = OUTPUT.resolve(name);
    if (WRITE) {
      Files.createDirectories(OUTPUT);
      Files.writeString(file, output, StandardCharsets.UTF_8);
    }
    assertThat(file)
        .as("docs/src/data/output/%s: ./gradlew :mutastic-cli:exportDocsOutput", name)
        .content(StandardCharsets.UTF_8)
        .isEqualTo(output);
  }
}
