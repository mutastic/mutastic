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

  /// Kotlin sources are a change as Java ones are: an edited `.kt` file's lines, and a new one's.
  @Test
  void kotlinChangesAreMutated() throws Exception {
    var project =
        Samples.copy(
            Path.of(System.getProperty("mutastic.samples"), "parameterized-kotlin"),
            work.resolve("parameterized-kotlin"));
    git(project, "init", "-q");
    git(project, "add", ".");
    git(project, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "start");
    var calculator = project.resolve("src/main/kotlin/com/example/calc/Calculator.kt");
    Files.writeString(
        calculator,
        Files.readString(calculator)
            .replace(
                "fun add(a: Int, b: Int): Int = a + b", "fun add(a: Int, b: Int): Int = b + a"));
    Files.writeString(
        calculator.resolveSibling("Twice.kt"),
        """
        package com.example.calc

        fun twice(x: Int): Int = x * 2
        """);

    var run =
        Samples.mutastic(
            "run", project.toString(), "--since=HEAD", "--format=jsonl", "--report=none");

    var lines = LINE.matcher(run.out()).results().map(found -> found.group(1)).distinct().toList();
    assertThat(lines).containsExactlyInAnyOrder("4", "3");
    assertThat(run.out())
        .contains("\"scope\":{\"since\":\"HEAD\",\"fileCount\":2,\"lineCount\":4}");
  }

  /// A run with no mutant to run starts no worker and runs no test, says so, and leaves the last
  /// run that measured something as the one `explain` and the next run read; `--force-coverage`
  /// runs the tests anyway.
  @Test
  void aCleanTreeRunsNoTest() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("pricing"));
    git(project, "init", "-q");
    git(project, "add", ".");
    git(project, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "start");
    assertThat(Samples.mutastic("run", project.toString(), "--format=brief").exit())
        .isEqualTo(Main.PASSED);
    var kept = project.resolve("build/mutastic");
    var before = keptFiles(kept);

    var empty = Samples.mutastic("run", project.toString(), "--since=HEAD", "--format=jsonl");

    assertThat(empty.exit()).isEqualTo(Main.PASSED);
    assertThat(empty.out())
        .doesNotContain("\"phase\":\"coverage\"")
        .doesNotContain("\"phase\":\"mutants\"")
        .contains("\"tests\":0,\"workers\":")
        .contains("\"workersStarted\":0,\"coverageMs\":0")
        .contains("\"score\":null")
        .contains("\"reports\":[]")
        .contains("\"scope\":{\"since\":\"HEAD\",\"fileCount\":0,\"lineCount\":0}");
    var text = Samples.mutastic("run", project.toString(), "--since=HEAD", "--format=brief");
    assertThat(text.out())
        .contains("  Changed  0 lines in 0 files since HEAD\n")
        .contains("    Tests  none ran: no mutant to run\n")
        .contains("    Score  none  [PASS] Nothing to measure: no mutants to score.")
        .doesNotContain("Test runs")
        .doesNotContain("Reports");
    assertThat(keptFiles(kept)).isEqualTo(before);

    var forced =
        Samples.mutastic(
            "run",
            project.toString(),
            "--since=HEAD",
            "--force-coverage",
            "--format=jsonl",
            "--report=none");

    assertThat(forced.exit()).isEqualTo(Main.PASSED);
    assertThat(forced.out()).contains("\"phase\":\"coverage\"").doesNotContain("\"tests\":0,");
  }

  /// The run record and the report files a run keeps, by name, with what's in them.
  private static java.util.Map<String, String> keptFiles(Path kept) throws Exception {
    var files = new java.util.TreeMap<String, String>();
    for (var name : List.of("last-run.tsv", "mutation.json", "mutation.html")) {
      files.put(name, Files.readString(kept.resolve(name)));
    }
    return files;
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
