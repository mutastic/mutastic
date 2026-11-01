package io.mutastic.engine;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// A worker process on the project's own JDK, which runs the project's tests (the protocol is
/// documented on `io.mutastic.worker.Worker`). One worker serves every mutant. A worker that
/// doesn't answer in time is killed, which is the only way to stop a mutant that loops forever,
/// and the next mutant starts a new one.
final class TestWorker implements AutoCloseable {
  private static final Duration STARTUP = Duration.ofSeconds(30);
  private static final Duration COVERAGE_LIMIT = Duration.ofMinutes(10);

  private final Project project;
  private final Path workerJar;
  private final Path log;
  private Process process;
  private Thread shutdownHook;
  private PrintStream commands;
  private BlockingQueue<String> answers;
  private int jdk;

  /// `log` collects the tests' own output, for when a baseline fails for reasons only the
  /// tests' output explains.
  TestWorker(Project project, Path workerJar, Path log) {
    this.project = project;
    this.workerJar = workerJar;
    this.log = log;
  }

  /// The tests' result with every class in `instrumented` swapped for its instrumented copy.
  record Baseline(boolean passed, List<String> failing) {}

  Baseline cover(Map<String, byte[]> instrumented, List<String> testClasses, Coverage coverage) {
    instrumented.forEach(this::stage);
    send("COVER\t" + String.join("\t", testClasses));
    while (true) {
      var answer = await(COVERAGE_LIMIT).orElseThrow(() -> {
        kill();
        return new IllegalStateException("the tests didn't finish within " + COVERAGE_LIMIT.toMinutes() + " minutes");
      });
      var parts = answer.split("\t");
      switch (parts[0]) {
        case "TEST" -> coverage.recordTest(parts[1], Duration.ofNanos(Long.parseLong(parts[2])));
        case "LINE" -> coverage.recordLines(parts[1],
            Arrays.stream(parts[2].split(",")).map(Integer::valueOf).collect(Collectors.toSet()));
        case "DONE" -> {
          return new Baseline(parts[1].equals("PASSED"), Arrays.asList(parts).subList(Math.min(2, parts.length), parts.length));
        }
        default -> throw new IllegalStateException("the worker said: " + answer);
      }
    }
  }

  /// The outcome of `tests`, run in order until one fails, with `bytes` in place of `className`.
  record Run(Status status, Optional<String> failedTest, Optional<String> failure, int testsRun) {}

  Run run(String className, byte[] bytes, List<String> tests, Duration timeout) {
    stage(className, bytes);
    send("RUN\t" + String.join("\t", tests));
    var answer = await(timeout);
    if (answer.isEmpty()) {
      kill();
      return new Run(Status.TIMED_OUT, Optional.empty(), Optional.empty(), tests.size());
    }
    var parts = answer.get().split("\t");
    return switch (parts[1]) {
      case "PASSED" -> new Run(Status.MISSED, Optional.empty(), Optional.empty(), Integer.parseInt(parts[2]));
      case "FAILED" -> new Run(Status.CAUGHT, Optional.of(parts[3]), Optional.of(parts[4]), Integer.parseInt(parts[2]));
      default -> throw new IllegalStateException("the worker said: " + answer.get());
    };
  }

  /// Whether `test` passes on its own with no mutant in, within `timeout`.
  boolean passes(String test, Duration timeout) {
    send("RUN\t" + test);
    var answer = await(timeout);
    if (answer.isEmpty()) {
      kill();
      return false;
    }
    return answer.get().startsWith("RESULT\tPASSED");
  }

  /// The JDK feature version the tests run on, once a worker has started.
  int jdk() {
    return jdk;
  }

  @Override
  public void close() {
    if (process != null) {
      commands.println("EXIT");
      commands.flush();
      process.destroy();
      forget();
    }
  }

  private void stage(String className, byte[] bytes) {
    send("CLASS\t" + className + "\t" + Base64.getEncoder().encodeToString(bytes));
  }

  private void send(String command) {
    ensureStarted();
    commands.println(command);
    commands.flush();
  }

  private Optional<String> await(Duration timeout) {
    try {
      return Optional.ofNullable(answers.poll(timeout.toNanos(), TimeUnit.NANOSECONDS));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  private void kill() {
    process.destroyForcibly();
    forget();
  }

  private void forget() {
    try {
      Runtime.getRuntime().removeShutdownHook(shutdownHook);
    } catch (IllegalStateException e) {
      // already shutting down: the hook is running
    }
    process = null;
  }

  private void ensureStarted() {
    if (process != null) {
      return;
    }
    var command = new ArrayList<String>();
    command.add(project.java().toString());
    command.addAll(project.jvmArgs());
    command.add("-javaagent:" + workerJar);
    command.addAll(List.of("-cp", join(Stream.concat(Stream.of(workerJar), project.dependencies().stream())),
        "io.mutastic.worker.Worker",
        "--production=" + join(Stream.concat(project.classes().stream(), project.resources().stream())),
        "--tests=" + join(Stream.concat(project.testClasses().stream(), project.testResources().stream()))));
    try {
      process = new ProcessBuilder(command)
          .directory(project.workingDirectory().toFile())
          .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
          .start();
    } catch (IOException e) {
      throw new UncheckedIOException("couldn't start " + project.java(), e);
    }
    commands = new PrintStream(process.getOutputStream(), false, UTF_8);
    // However the engine exits, its workers go too.
    var started = process;
    shutdownHook = new Thread(started::destroyForcibly);
    Runtime.getRuntime().addShutdownHook(shutdownHook);
    // A queue per process, so nothing a killed worker was still saying reaches the next one.
    var queue = new LinkedBlockingQueue<String>();
    answers = queue;
    var reader = new BufferedReader(new InputStreamReader(process.getInputStream(), UTF_8));
    Thread.ofVirtual().start(() -> {
      try {
        for (String line = reader.readLine(); line != null; line = reader.readLine()) {
          queue.add(line);
        }
      } catch (IOException e) {
        // the worker was killed; whatever it was saying no longer matters
      }
    });
    var ready = await(STARTUP).filter(line -> line.startsWith("READY\t")).orElseThrow(() ->
        new IllegalStateException("the test worker didn't start; its output is in " + log));
    jdk = Integer.parseInt(ready.substring("READY\t".length()));
  }

  private static String join(Stream<Path> paths) {
    return paths.map(Path::toString).collect(Collectors.joining(File.pathSeparator));
  }
}
