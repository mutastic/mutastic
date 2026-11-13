package io.mutastic.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LogTailTest {
  @TempDir Path work;

  @Test
  void theLatestLineWithSomethingOnIt() throws IOException {
    var log = work.resolve("gradle.log");
    var tail = new LogTail(log);

    assertThat(tail.latest()).isEmpty();
    Files.writeString(log, "> Task :compileJava\n> Task :compileTestJava\n\n");
    assertThat(tail.latest()).contains("> Task :compileTestJava");
    Files.writeString(log, "x".repeat(10_000) + "\n\u001b[1m[INFO]\u001b[0m Building\r\n");
    assertThat(tail.latest()).contains("[INFO] Building");
  }
}
