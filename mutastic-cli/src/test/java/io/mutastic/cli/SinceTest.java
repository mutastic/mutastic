package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// `--since`: only the lines changed since a commit are mutated, uncommitted and new files
/// included, as a pull request or an agent's change would have them.
class SinceTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final Pattern LINE = Pattern.compile("\"line\":(\\d+)");

  @TempDir Path work;

  @Test
  void onlyChangedLinesAreMutated() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    git(project, "init", "-q");
    git(project, "add", ".");
    git(project, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "start");
    var pricing = project.resolve("src/main/java/com/example/pricing/Pricing.java");
    Files.writeString(
        pricing,
        Files.readString(pricing).replace("return total / 10;", "return total / 10; // a tenth"));
    Files.writeString(
        pricing.resolveSibling("Rounding.java"),
        """
        package com.example.pricing;

        public class Rounding {
          public int up(int cents) {
            return cents + 99;
          }
        }
        """);

    var run =
        Samples.mutastic(
            "run", project.toString(), "--since=HEAD", "--format=jsonl", "--report=none");

    var lines = LINE.matcher(run.out()).results().map(found -> found.group(1)).distinct().toList();
    assertThat(lines).containsExactlyInAnyOrder("18", "5");
    assertThat(run.out())
        .contains("\"scope\":{\"since\":\"HEAD\",\"fileCount\":2,\"lineCount\":8}");
  }

  @Test
  void aDirectoryOutsideGitSaysWhatsNeeded() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));

    var run = Samples.mutastic("run", project.toString(), "--since=main");

    assertThat(run.exit()).isEqualTo(Main.USAGE);
    assertThat(run.err()).contains("--since needs a git repository");
  }

  private static void git(Path directory, String... args) throws Exception {
    var command = new java.util.ArrayList<>(List.of("git", "-C", directory.toString()));
    command.addAll(List.of(args));
    assertThat(new ProcessBuilder(command).inheritIO().start().waitFor()).isZero();
  }
}
