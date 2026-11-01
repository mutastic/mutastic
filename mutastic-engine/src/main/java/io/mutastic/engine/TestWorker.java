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
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/// A worker process on the project's own JDK, which runs the project's tests (the protocol is
/// documented on `io.mutastic.worker.Worker`). One worker serves every mutant. A worker that
/// doesn't answer in time is killed, which is the only way to stop a mutant that loops forever, and
/// the next mutant starts a new one.
final class TestWorker implements AutoCloseable {
  private static final Duration STARTUP = Duration.ofSeconds(30);
  private static final Duration COVERAGE_LIMIT = Duration.ofMinutes(10);

  /// Put on a worker's answers when its process exits: no answer is coming, so nothing waits for
  /// one. Not a line a worker can write.
  private static final String STOPPED = "\0stopped";

  private final Project project;
  private final Path workerJar;
  private final Path log;

  /// The worker process while one is running: none before the first command, or after a kill.
  private @Nullable Running running;

  private int jdk;
  private int workersStarted;

  /// `log` collects the tests' own output, for when a baseline fails for reasons only the tests'
  /// output explains.
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
      var answer = coverageAnswer();
      var parts = answer.split("\t", -1);
      switch (parts[0]) {
        case "TEST" -> coverage.recordTest(parts[1], Duration.ofNanos(Long.parseLong(parts[2])));
        case "LINE" -> coverage.recordLines(parts[1], commaSeparated(parts[2], Integer::valueOf));
        case "VALUE" ->
            coverage.recordValues(
                parts[1], Integer.parseInt(parts[2]), commaSeparated(parts[3], Long::valueOf));
        case "DONE" -> {
          return new Baseline(
              parts[1].equals("PASSED"),
              Arrays.asList(parts).subList(Math.min(2, parts.length), parts.length));
        }
        default -> throw new IllegalStateException("the worker said: " + answer);
      }
    }
  }

  /// The worker's next answer in the coverage pass. A pass that outlasts its limit kills the
  /// worker.
  private String coverageAnswer() {
    return await(COVERAGE_LIMIT)
        .orElseThrow(
            () -> {
              var current = running;
              boolean stopped = current != null && current.stopped().get();
              kill();
              return new IllegalStateException(
                  stopped
                      ? "the test worker stopped during the coverage pass; its output is in " + log
                      : "the tests didn't finish within "
                          + COVERAGE_LIMIT.toMinutes()
                          + " minutes");
            });
  }

  private static <T> Set<T> commaSeparated(String values, Function<String, T> parse) {
    return Arrays.stream(values.split(",", -1)).map(parse).collect(Collectors.toSet());
  }

  /// The outcome of `tests`, run in order until one fails, with `bytes` in place of `className`.
  ///
  /// @param origin where the failure came from: `assertion`, `test` or `code`
  /// @param portClash whether a port in use caused the failure, which with workers side by side can
  ///   be another worker's doing
  record Run(
      Status status,
      Optional<String> failedTest,
      Optional<String> failure,
      Optional<String> origin,
      int testsRun,
      boolean portClash) {

    Run(Status status, int testsRun) {
      this(status, Optional.empty(), Optional.empty(), Optional.empty(), testsRun, false);
    }
  }

  Run run(String className, byte[] bytes, List<String> tests, Duration timeout) {
    stage(className, bytes);
    send("RUN\t" + String.join("\t", tests));
    var answer = await(timeout);
    if (answer.isEmpty()) {
      kill();
      return new Run(Status.TIMED_OUT, tests.size());
    }
    var parts = answer.get().split("\t", -1);
    return switch (parts[1]) {
      case "PASSED" -> new Run(Status.MISSED, Integer.parseInt(parts[2]));
      case "FAILED" ->
          new Run(
              Status.CAUGHT,
              Optional.of(parts[3]),
              Optional.of(parts[4]),
              Optional.of(parts[5]),
              Integer.parseInt(parts[2]),
              parts[6].equals("port-clash"));
      default -> throw new IllegalStateException("the worker said: " + answer.get());
    };
  }

  /// The first of `candidates` (one value per constructor parameter of `receiver`, then per method
  /// parameter) where `bytes` changes what the method returns, with both results; empty if none
  /// does in the worker's time budget. `receiver` is `-` for a static method, else the constructor
  /// descriptor. A call that never returned on the mutant leaves a thread running, so the worker is
  /// replaced.
  Optional<String[]> suggest(
      Mutant mutant, String receiver, byte[] bytes, List<List<Long>> candidates) {
    send(suggestCommand(mutant, receiver, bytes, candidates));
    var answer = await(Duration.ofSeconds(10));
    if (answer.isEmpty()) {
      kill();
      return Optional.empty();
    }
    var parts = answer.get().split("\t", -1);
    if (!parts[0].equals("SUGGESTED")) {
      throw new IllegalStateException("the worker said: " + answer.get());
    }
    if (parts.length >= 5 && parts[4].equals("stuck")) {
      kill(); // a call that never returned: a timeout in all but name
    }
    return parts[1].equals("-")
        ? Optional.empty()
        : Optional.of(new String[] {parts[1], parts[2], parts[3]});
  }

  private static String suggestCommand(
      Mutant mutant, String receiver, byte[] bytes, List<List<Long>> candidates) {
    return String.join(
        "\t",
        "SUGGEST",
        mutant.binaryName(),
        mutant.methodName(),
        mutant.descriptor(),
        Base64.getEncoder().encodeToString(bytes),
        encoded(candidates),
        receiver);
  }

  /// `1;2|-|3`: each candidate's values joined by `;`, `-` for none, and the candidates by `|`.
  private static String encoded(List<List<Long>> candidates) {
    return candidates.stream()
        .map(
            inputs ->
                inputs.isEmpty()
                    ? "-"
                    : inputs.stream().map(String::valueOf).collect(Collectors.joining(";")))
        .collect(Collectors.joining("|"));
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

  /// How many worker JVMs have started: one, plus one after each kill.
  int started() {
    return workersStarted;
  }

  /// The JDK feature version the tests run on, once a worker has started.
  int jdk() {
    return jdk;
  }

  /// A running worker: its process, what's sent to it, what it has said, and the hook that ends it
  /// if the engine exits first. A queue per process, so nothing a killed worker was still saying
  /// reaches the next one.
  ///
  /// `stopped` is set once the process's output ends: on Linux that comes before the process is
  /// reaped, so `isAlive()` can't tell yet.
  private record Running(
      Process process,
      PrintStream commands,
      BlockingQueue<String> answers,
      Thread shutdownHook,
      AtomicBoolean stopped) {}

  @Override
  public void close() {
    var current = running;
    if (current != null) {
      current.commands().println("EXIT");
      current.commands().flush();
      current.process().destroy();
      forget(current);
    }
  }

  private void stage(String className, byte[] bytes) {
    send("CLASS\t" + className + "\t" + Base64.getEncoder().encodeToString(bytes));
  }

  private void send(String command) {
    var commands = running().commands();
    commands.println(command);
    commands.flush();
  }

  /// The running worker's next answer, or empty if it gave none in time, stopped, or none is
  /// running.
  private Optional<String> await(Duration timeout) {
    var current = running;
    if (current == null) {
      return Optional.empty();
    }
    try {
      return Optional.ofNullable(current.answers().poll(timeout.toNanos(), TimeUnit.NANOSECONDS))
          .filter(answer -> !answer.equals(STOPPED));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  private void kill() {
    var current = running;
    if (current != null) {
      current.process().destroyForcibly();
      forget(current);
    }
  }

  private void forget(Running current) {
    try {
      Runtime.getRuntime().removeShutdownHook(current.shutdownHook());
    } catch (IllegalStateException e) {
      // already shutting down: the hook is running
    }
    running = null;
  }

  /// The running worker, started if none is.
  private Running running() {
    var current = running;
    return current != null ? current : start();
  }

  private Running start() {
    var process = launch(command());
    // However the engine exits, its workers go too.
    var shutdownHook = new Thread(process::destroyForcibly);
    Runtime.getRuntime().addShutdownHook(shutdownHook);
    var queue = new LinkedBlockingQueue<String>();
    var started =
        new Running(
            process,
            new PrintStream(process.getOutputStream(), false, UTF_8),
            queue,
            shutdownHook,
            new AtomicBoolean());
    running = started;
    forwardAnswers(started);
    var ready =
        await(STARTUP)
            .filter(line -> line.startsWith("READY\t"))
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "the test worker didn't start; its output is in " + log));
    jdk = Integer.parseInt(ready.substring("READY\t".length()));
    workersStarted++;
    return started;
  }

  /// The worker's command line: the project's own JDK and JVM arguments, with the worker as agent.
  private List<String> command() {
    var command = new ArrayList<String>();
    command.add(project.java().toString());
    command.addAll(project.jvmArgs());
    command.add("-javaagent:" + workerJar);
    command.addAll(
        List.of(
            "-cp",
            join(Stream.concat(Stream.of(workerJar), project.dependencies().stream())),
            "io.mutastic.worker.Worker",
            "--production="
                + join(Stream.concat(project.classes().stream(), project.resources().stream())),
            "--tests="
                + join(
                    Stream.concat(
                        project.testClasses().stream(), project.testResources().stream()))));
    return command;
  }

  private Process launch(List<String> command) {
    try {
      return new ProcessBuilder(command)
          .directory(project.workingDirectory().toFile())
          .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
          .start();
    } catch (IOException e) {
      throw new UncheckedIOException("couldn't start " + project.java(), e);
    }
  }

  /// Every line the worker writes goes on `queue`, read on a thread of its own.
  private static void forwardAnswers(Running worker) {
    var queue = worker.answers();
    var reader =
        new BufferedReader(new InputStreamReader(worker.process().getInputStream(), UTF_8));
    Thread.ofVirtual()
        .start(
            () -> {
              try {
                for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                  queue.add(line);
                }
              } catch (IOException e) {
                // the worker was killed; whatever it was saying no longer matters
              }
              worker.stopped().set(true);
              queue.add(STOPPED);
            });
  }

  private static String join(Stream<Path> paths) {
    return paths.map(Path::toString).collect(Collectors.joining(File.pathSeparator));
  }
}
