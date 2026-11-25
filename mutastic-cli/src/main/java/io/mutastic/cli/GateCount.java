package io.mutastic.cli;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/// The test cases running when a gated task starts, counted by a run: what only running says, as
/// the rows a `@MethodSource` makes, a test a condition turns off, and a case an assumption aborts.
///
/// `gate open` starts the count and returns at once: a process of its own runs the tests, with no
/// mutant, on a copy of the working tree as it was then, in a temporary directory, so the task can
/// change its files meanwhile and the person's tree is never touched. It writes what it counted to
/// [#RESULT] in the gate's work directory, and removes its copy. `gate check` waits for it, within
/// [#waitFor], and compares it with its own run's count; `gate close` stops it.
final class GateCount {
  /// The count started: its process, its copy, and the token its result must carry.
  static final String STARTED = "gate-count.properties";

  /// What it counted, or why it couldn't.
  static final String RESULT = "gate-cases.properties";

  private static final String COMMENT = "mutastic gate: the count of the task's start";

  /// What each count's copy is called, before the temporary directory's own suffix.
  private static final String COPY = "mutastic-gate-start";

  /// In a copy: the count's process that owns it, by its id and when it started.
  private static final String OWNER = "owner.properties";

  /// How old a copy is before a gate opening takes it for left behind.
  static final Duration LEFT_BEHIND = Duration.ofDays(1);

  private GateCount() {}

  /// The module counted, as the gate's command names it.
  ///
  /// @param buildArguments what `gate open` was given after `--`, for this count alone: kept in no
  ///   file
  record Module(String name, String suite, Optional<List<String>> buildArguments) {}

  /// What the count found at the task's start.
  ///
  /// @param cases the test cases run; -1 if they couldn't be counted
  /// @param skipped those turned off or aborted
  /// @param why why they couldn't be counted, when they couldn't
  record Counted(int cases, int skipped, String why) {
    boolean counted() {
      return cases >= 0;
    }

    static Counted failed(String why) {
      return new Counted(-1, -1, why);
    }
  }

  /// Starts the count of the working tree `tree` of the project at `directory`; when it can't
  /// start, its result says why, which a check says.
  static void start(Path work, Path directory, String tree, Module module) {
    stop(work);
    sweep(Path.of(System.getProperty("java.io.tmpdir")), java.time.Instant.now());
    try {
      var top = new Git(directory).toplevel();
      var project = top.relativize(directory.toRealPath()).toString();
      var copy = Files.createTempDirectory(COPY);
      var token = UUID.randomUUID().toString();
      var arguments =
          new java.util.ArrayList<>(
              List.of(
                  work.toAbsolutePath().toString(),
                  top.toString(),
                  tree,
                  project.isEmpty() ? "." : project,
                  copy.toString(),
                  token,
                  module.name(),
                  module.suite()));
      module
          .buildArguments()
          .ifPresent(
              given -> {
                arguments.add(BuildArgs.START);
                arguments.addAll(given);
              });
      started(work, spawn(work, arguments), copy, token);
    } catch (IOException | RuntimeException e) {
      var failed = new Properties();
      failed.setProperty("why", "the count couldn't start: " + e.getMessage());
      write(work.resolve(RESULT), failed);
    }
  }

  /// What a check needs to find the count, and close to stop it.
  private static void started(Path work, Process process, Path copy, String token) {
    var started = new Properties();
    started.setProperty("pid", String.valueOf(process.pid()));
    started.setProperty("copy", copy.toString());
    started.setProperty("token", token);
    write(work.resolve(STARTED), started);
  }

  /// A process, as a copy's owner: its id, and when it started, so an id used again since isn't
  /// taken for it.
  static Properties owner(ProcessHandle process) {
    var owner = new Properties();
    owner.setProperty("pid", String.valueOf(process.pid()));
    process
        .info()
        .startInstant()
        .ifPresent(start -> owner.setProperty("started", String.valueOf(start.toEpochMilli())));
    return owner;
  }

  /// Removes the copies in `temp` that counts left behind: older than [#LEFT_BEHIND] at `now`, and
  /// owned by no count still running. A count removes its own copy when it ends, but one stopped
  /// hard, as when its build's daemon is killed, can't.
  static void sweep(Path temp, java.time.Instant now) {
    try (var copies = Files.newDirectoryStream(temp, COPY + "*")) {
      for (var copy : copies) {
        if (leftBehind(copy, now)) {
          delete(copy);
        }
      }
    } catch (IOException | UncheckedIOException e) {
      // only tidying: a gate opens without it
    }
  }

  private static boolean leftBehind(Path copy, java.time.Instant now) throws IOException {
    return Files.isDirectory(copy)
        && Files.getLastModifiedTime(copy).toInstant().isBefore(now.minus(LEFT_BEHIND))
        && read(copy.resolve(OWNER)).filter(GateCount::running).isEmpty();
  }

  /// Whether a copy's owner is still running: the same process, not another given its id since.
  private static boolean running(Properties owner) {
    var started = owner.getProperty("started", "");
    return process(owner)
        .filter(ProcessHandle::isAlive)
        .filter(
            process ->
                started.isEmpty()
                    || process
                        .info()
                        .startInstant()
                        .map(start -> String.valueOf(start.toEpochMilli()).equals(started))
                        .orElse(true))
        .isPresent();
  }

  /// This class's [#main] in a JVM of its own, as this one runs, its output in the work directory.
  private static Process spawn(Path work, List<String> arguments) throws IOException {
    var java =
        ProcessHandle.current()
            .info()
            .command()
            .orElse(Path.of(System.getProperty("java.home"), "bin", "java").toString());
    var command =
        new java.util.ArrayList<>(
            List.of(java, "-cp", System.getProperty("java.class.path"), GateCount.class.getName()));
    command.addAll(arguments);
    Files.createDirectories(work);
    var nothing = System.getProperty("os.name").startsWith("Windows") ? "NUL" : "/dev/null";
    return new ProcessBuilder(command)
        .redirectInput(ProcessBuilder.Redirect.from(new java.io.File(nothing)))
        .redirectOutput(work.resolve("gate-count.log").toFile())
        .redirectErrorStream(true)
        .start();
  }

  /// The count, once it's done, waiting up to `wait` for it; failed, saying why, when it isn't done
  /// by then, its process is gone, or none was started.
  static Counted waitFor(Path work, Duration wait) {
    long until = System.nanoTime() + wait.toNanos();
    var now = now(work);
    while (now.isEmpty()) {
      if (System.nanoTime() > until) {
        return Counted.failed("the count didn't finish in " + wait.toSeconds() + "s");
      }
      try {
        Thread.sleep(250);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        return Counted.failed("interrupted");
      }
      now = now(work);
    }
    return now.get();
  }

  /// The count, if it's done or can't be; none while it's running.
  private static Optional<Counted> now(Path work) {
    var started = read(work.resolve(STARTED));
    var result = read(work.resolve(RESULT));
    if (result.isPresent()) {
      var found = result.get();
      var token = started.map(it -> it.getProperty("token", "")).orElse("");
      return Optional.of(
          found.getProperty("cases") != null && token.equals(found.getProperty("token", ""))
              ? new Counted(
                  Integer.parseInt(found.getProperty("cases")),
                  Integer.parseInt(found.getProperty("skipped", "0")),
                  "")
              : Counted.failed(found.getProperty("why", "the count didn't finish")));
    }
    if (started.isEmpty()) {
      return Optional.of(Counted.failed("no count was started"));
    }
    var running = process(started.get()).map(ProcessHandle::isAlive).orElse(false);
    return running
        ? Optional.empty()
        : Optional.of(Counted.failed("the count stopped before it finished"));
  }

  /// The count's process ended, and what it started, before its copy goes: one still running would
  /// write into it again.
  private static void ended(ProcessHandle process) {
    var started = process.descendants().toList();
    started.forEach(ProcessHandle::destroy);
    process.destroy();
    try {
      process.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS);
    } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
      process.destroyForcibly();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    started.forEach(ProcessHandle::destroyForcibly);
  }

  private static Optional<ProcessHandle> process(Properties started) {
    return ProcessHandle.of(Long.parseLong(started.getProperty("pid", "-1")));
  }

  /// Stops a count still running, and forgets it and its result: the process and what it started
  /// end, and its copy goes.
  static void stop(Path work) {
    read(work.resolve(STARTED))
        .ifPresent(
            started -> {
              process(started).ifPresent(GateCount::ended);
              var copy = started.getProperty("copy", "");
              if (!copy.isEmpty()) {
                delete(Path.of(copy));
              }
            });
    try {
      Files.deleteIfExists(work.resolve(STARTED));
      Files.deleteIfExists(work.resolve(RESULT));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  /// The count itself, in its own process: `work`, the repository, the tree, the project's path in
  /// it, the copy, the token, the module and the suite.
  public static void main(String[] args) {
    var work = Path.of(args[0]);
    var token = args[5];
    var result = counted(args);
    result.setProperty("token", token);
    // Only for the gate that started it: a gate closed or opened again meanwhile has its own.
    var started = read(work.resolve(STARTED));
    if (started.filter(it -> token.equals(it.getProperty("token"))).isPresent()) {
      write(work.resolve(RESULT), result);
    }
    System.exit(0);
  }

  /// What [#main] counted, or why it couldn't; its copy is gone after.
  private static Properties counted(String[] args) {
    var copy = Path.of(args[4]);
    var result = new Properties();
    try {
      write(copy.resolve(OWNER), owner(ProcessHandle.current()));
      var root = copied(Path.of(args[1]), args[2], copy);
      var counted =
          Main.countCases(
              root.resolve(args[3]).normalize(), args[6], args[7], BuildArgs.typed(List.of(args)));
      result.setProperty("cases", String.valueOf(counted[0]));
      result.setProperty("skipped", String.valueOf(counted[1]));
    } catch (io.mutastic.engine.Engine.BaselineFailed e) {
      result.setProperty("why", "the tests failed at the task's start");
    } catch (RuntimeException | IOException e) {
      result.setProperty("why", "the count failed: " + e.getMessage());
    } finally {
      delete(copy);
    }
    return result;
  }

  /// A commit of everything, by no one in particular.
  private static final List<String> COMMIT =
      List.of(
          "git",
          "-c",
          "user.name=mutastic",
          "-c",
          "user.email=mutastic@localhost",
          "-c",
          "commit.gpgsign=false",
          "commit",
          "-q",
          "--allow-empty",
          "-m",
          "the task's start");

  /// The files of `tree` in `repository`, in `copy`, committed for the run's `--since`: it mutates
  /// nothing, and runs every test.
  private static Path copied(Path repository, String tree, Path copy) throws IOException {
    var root = Files.createDirectories(copy.resolve("tree"));
    var tar = copy.resolve("tree.tar");
    new Git(repository).archive(tree, tar);
    run(copy, List.of("tar", "-xf", tar.toString(), "-C", root.toString()));
    Files.delete(tar);
    run(root, List.of("git", "init", "-q"));
    run(root, List.of("git", "add", "-A"));
    run(root, COMMIT);
    return root;
  }

  private static void run(Path directory, List<String> command) throws IOException {
    try {
      var process =
          new ProcessBuilder(command)
              .directory(directory.toFile())
              .redirectErrorStream(true)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (process.waitFor() != 0) {
        throw new IOException(String.join(" ", command) + " failed");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IOException("interrupted", e);
    }
  }

  private static Optional<Properties> read(Path file) {
    if (!Files.isRegularFile(file)) {
      return Optional.empty();
    }
    var properties = new Properties();
    try (var in = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
      properties.load(in);
      return Optional.of(properties);
    } catch (IOException e) {
      return Optional.empty();
    }
  }

  /// Written whole or not at all: a reader never sees half of it.
  private static void write(Path file, Properties properties) {
    try {
      Files.createDirectories(file.getParent());
      var partial = file.resolveSibling(file.getFileName() + ".partial");
      try (var out = Files.newBufferedWriter(partial, StandardCharsets.UTF_8)) {
        properties.store(out, COMMENT);
      }
      Files.move(
          partial, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void delete(Path directory) {
    if (!Files.exists(directory)) {
      return;
    }
    try (var paths = Files.walk(directory)) {
      for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(path);
      }
    } catch (IOException | UncheckedIOException e) {
      // a temporary directory: what's left, the system clears
    }
  }
}
