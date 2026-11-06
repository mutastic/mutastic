package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/// A worker killed mid-test, as one running a mutant that never finishes is, ends no test, so the
/// test framework never deletes that test's temporary directory. And a process a test starts and
/// leaves running outlives its worker, however the worker ends, unless it's ended too.
/// `samples/scratch` lists each directory its test makes and each process it starts, and waits
/// forever on a mutant that breaks it.
class ScratchTest {
  private static final Path SAMPLE = Path.of(System.getProperty("mutastic.samples"), "scratch");

  @TempDir Path work;

  @Test
  void aWorkerLeavesNothingBehindHoweverItEnds() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("scratch"));

    var run = Samples.mutastic("run", project.toString(), "--format=jsonl", "--workers=1");

    assertThat(run.out()).contains("\"status\":\"Timeout\"");
    assertThat(Files.readAllLines(project.resolve("build/scratch.txt")))
        .hasSizeGreaterThan(1)
        .allSatisfy(made -> assertThat(Path.of(made)).doesNotExist());
    // One worker killed for each timeout, and the last told to exit.
    assertThat(Files.readAllLines(project.resolve("build/children.txt")))
        .hasSizeGreaterThan(1)
        .allSatisfy(ScratchTest::hasEnded);
  }

  private static void hasEnded(String pid) {
    ProcessHandle.of(Long.parseLong(pid))
        .ifPresent(child -> assertThat(child.onExit()).succeedsWithin(Duration.ofSeconds(10)));
  }

  /// A project that names its own temporary directory keeps it, and keeps what's left in it.
  @Test
  void aProjectsOwnTemporaryDirectoryWins() throws Exception {
    var project = Samples.copy(SAMPLE, work.resolve("scratch"));
    var own = Files.createDirectories(work.resolve("own-temp"));
    Files.writeString(
        project.resolve("build.gradle.kts"),
        "tasks.test { jvmArgs(\"-Djava.io.tmpdir=" + own + "\") }\n",
        StandardOpenOption.APPEND);

    Samples.mutastic("run", project.toString(), "--format=jsonl", "--workers=1");

    assertThat(Files.readAllLines(project.resolve("build/scratch.txt")))
        .isNotEmpty()
        .allSatisfy(made -> assertThat(Path.of(made)).hasParentRaw(own));
  }
}
