package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A worker that has got slow is replaced, and what the run finds doesn't change: here the sample's
/// JVM arguments have that happen after every mutant.
class ReplacedWorkersTest {
  private static final Path LOADING = Path.of(System.getProperty("mutastic.samples"), "loading");
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "pricing");
  private static final Pattern STATUS =
      Pattern.compile("\"id\":\"(?<id>m[0-9a-f]+)\".*?\"status\":\"(?<status>[A-Za-z]+)\"");

  @TempDir Path work;

  @Test
  void aWorkerReplacedAfterEachMutantFindsTheSame() throws Exception {
    var kept = Samples.copy(SAMPLE, work.resolve("kept"));
    var replaced = Samples.copy(SAMPLE, work.resolve("replaced"));
    replacedAfterEachMutant(replaced);

    var keptRun = Samples.mutastic("run", kept.toString(), "--format=jsonl", "--workers=1");
    var replacedRun = Samples.mutastic("run", replaced.toString(), "--format=jsonl", "--workers=1");

    assertThat(statuses(replacedRun.out())).isNotEmpty().isEqualTo(statuses(keptRun.out()));
    assertThat(log(replaced).lines().filter(line -> line.equals("AGED")))
        .as("workers that said they'd aged")
        .hasSizeGreaterThan(5);
    assertThat(log(kept)).doesNotContain("AGED");
  }

  /// A new worker initialises what the coverage pass did, with no mutant in. If it didn't, this
  /// boundary, which only the static initialiser's `check(0)` could show and no test looks at,
  /// would run in that initialiser and break `Shipping`: a catch, and no test caught it.
  @Test
  void aMutantInWhatAStaticInitialiserCallsDoesntBreakAReplacedWorker() throws Exception {
    var kept = withAStaticInitialiser(work.resolve("kept"));
    var replaced = withAStaticInitialiser(work.resolve("replaced"));
    replacedAfterEachMutant(replaced);

    var keptRun = Samples.mutastic("run", kept.toString(), "--format=jsonl", "--workers=1");
    var replacedRun = Samples.mutastic("run", replaced.toString(), "--format=jsonl", "--workers=1");

    assertThat(replacedRun.out()).doesNotContain("NoClassDefFoundError");
    assertThat(statuses(replacedRun.out())).isNotEmpty().isEqualTo(statuses(keptRun.out()));
    assertThat(
            replacedRun
                .out()
                .lines()
                .filter(line -> line.contains("\"class\":\"com.example.loading.Table\""))
                .filter(line -> line.contains("becomes `<=`")))
        .singleElement()
        .asString()
        .contains("\"status\":\"Survived\"");
  }

  private static Path withAStaticInitialiser(Path to) throws IOException {
    var project = Samples.copy(LOADING, to);
    var main = project.resolve("src/main/java/com/example/loading");
    Files.writeString(
        main.resolve("Table.java"),
        """
        package com.example.loading;

        public final class Table {
          private Table() {}

          public static int check(int value) {
            if (value < 0) {
              throw new IllegalArgumentException();
            }
            return value;
          }
        }
        """);
    Files.writeString(
        main.resolve("Shipping.java"),
        """
        package com.example.loading;

        public final class Shipping {
          private static final int BASE = Table.check(0);

          private Shipping() {}

          public static int cost(int weight) {
            return BASE + Table.check(weight);
          }
        }
        """);
    Files.writeString(
        project.resolve("src/test/java/com/example/loading/ShippingTest.java"),
        """
        package com.example.loading;

        import static org.junit.jupiter.api.Assertions.assertEquals;

        import org.junit.jupiter.api.Test;

        class ShippingTest {
          @Test
          void weightIsWhatItCosts() {
            assertEquals(5, Shipping.cost(5));
          }
        }
        """);
    return project;
  }

  private static void replacedAfterEachMutant(Path project) throws IOException {
    Files.writeString(
        project.resolve("build.gradle.kts"),
        "\ntasks.test { jvmArgs(\"-Dmutastic.worthANewWorkerMillis=0\") }\n",
        StandardOpenOption.APPEND);
  }

  /// What the worker wrote, wherever the run keeps it for this project.
  private static String log(Path project) throws IOException {
    try (var files = Files.walk(project.resolve("build/mutastic"))) {
      return Files.readString(
          files.filter(file -> file.endsWith("worker.log")).findFirst().orElseThrow());
    }
  }

  private static Map<String, String> statuses(String jsonl) {
    var statuses = new TreeMap<String, String>();
    jsonl
        .lines()
        .filter(line -> line.contains("\"reason\":\"mutant-finished\""))
        .map(STATUS::matcher)
        .filter(java.util.regex.Matcher::find)
        .forEach(found -> statuses.put(found.group("id"), found.group("status")));
    return statuses;
  }
}
