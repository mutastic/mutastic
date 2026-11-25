package io.mutastic.engine;

import static java.nio.charset.StandardCharsets.UTF_8;

import io.mutastic.api.BuildJava;
import io.mutastic.api.Mutant;
import io.mutastic.api.Project;
import io.mutastic.api.PropertyTests;
import io.mutastic.api.Status;
import io.mutastic.api.TestCase;
import io.mutastic.engine.tests.StartFailure;
import io.mutastic.engine.tests.TestFrameworks;
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
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/// A worker process on the project's own JDK, which runs the project's tests (the protocol is
/// documented on `io.mutastic.worker.Worker`). One worker serves every mutant. A worker that
/// doesn't answer in time is killed, which is the only way to stop a mutant that loops forever, and
/// the next mutant starts a new one; so does a worker that says it has aged, once it has answered.
/// Each worker has a temporary directory of its own ([WorkerTemp]): what one mutant's tests leave
/// there is deleted before the next mutant runs, the directory is deleted once the worker has gone,
/// and what its tests started goes with it.
final class TestWorker implements AutoCloseable {
  private static final Duration STARTUP = Duration.ofSeconds(30);
  static final Duration COVERAGE_LIMIT = Duration.ofMinutes(10);

  /// Put on a worker's answers when its process exits: no answer is coming, so nothing waits for
  /// one. Not a line a worker can write.
  private static final String STOPPED = "\0stopped";

  /// Said by a worker that's now worth replacing, ahead of its answer to a command.
  private static final String AGED = "AGED";

  /// Said by a worker as a test's code ends its JVM, with the test, in place of an answer.
  private static final String EXITED = "EXITED\t";

  private final Project project;

  /// What the project's tests need to run, and how the worker runs them.
  private final TestFrameworks frameworks;

  private final Path workerJar;
  private final Path log;
  private final ClassesToWarm toWarm;

  /// The project's resources, for its libraries to find ([ResourceCopy]).
  private final Path resources;

  /// The worker process while one is running: none before the first command, or after a kill.
  private @Nullable Running running;

  private int jdk;
  private int workersStarted;

  /// Whether the running worker has said it has aged.
  private boolean aged;

  /// The test whose code ended the worker's JVM, if the worker said one did since the last command.
  private @Nullable String exitedDuring;

  /// The single test methods it leaves out of each class it runs, `PricingTest#bigOrders` or
  /// `com.example.PricingTest#bigOrders`: every overload and row of each.
  private List<String> methodsLeftOut = List.of();

  /// `log` collects the tests' own output, for when a baseline fails for reasons only the tests'
  /// output explains.
  TestWorker(Project project, Path workerJar, Path log, ClassesToWarm toWarm, Path resources) {
    this.project = project;
    this.frameworks = TestFrameworks.of(project.dependencies());
    this.workerJar = workerJar;
    this.log = log;
    this.toWarm = toWarm;
    this.resources = resources;
  }

  /// This worker, leaving out these test methods; told before it starts.
  TestWorker leavingOut(List<String> methods) {
    methodsLeftOut = List.copyOf(methods);
    return this;
  }

  /// A worker for the same project, not yet started, that writes its tests' output to `log` and
  /// warms the same classes when it starts.
  TestWorker another(Path log) {
    return new TestWorker(project, workerJar, log, toWarm, resources).leavingOut(methodsLeftOut);
  }

  /// The tests' result with every class in `instrumented` swapped for its instrumented copy.
  ///
  /// @param failures each failing test's exception and its causes, `a.B>c.D`
  /// @param messages each failing test's message, its first line: what its assertion said
  /// @param cases each failing test's case that failed, when it runs a table of them
  record Baseline(
      boolean passed,
      List<String> failing,
      Map<String, String> failures,
      Map<String, String> messages,
      Map<String, TestCase> cases) {

    Baseline(
        boolean passed,
        List<String> failing,
        Map<String, String> failures,
        Map<String, String> messages) {
      this(passed, failing, failures, messages, Map.of());
    }
  }

  /// The tests' JVM stopped, or didn't finish, in the coverage pass, with no mutant in: the cause
  /// is in the tests, not in mutastic.
  static final class Stopped extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    private final boolean exited;
    private final @Nullable String running;

    Stopped(boolean exited, Optional<String> running) {
      super(exited ? "the tests' JVM stopped" : "the tests ran past the coverage pass's limit");
      this.exited = exited;
      this.running = running.orElse(null);
    }

    /// Whether the JVM ended, rather than ran on past the limit.
    boolean exited() {
      return exited;
    }

    /// The test that was running when it stopped, if one was.
    Optional<String> running() {
      return Optional.ofNullable(running);
    }
  }

  /// Sends the coverage pass, with every class in `instrumented` staged; first stops a run whose
  /// tests a worker can't run, before one starts: on a JDK older than the worker runs on
  /// ([Engine.TestsJdkTooOld]), or with a classpath a framework says what to add to or change in
  /// ([TestFrameworks#refusal]).
  private void startCovering(Map<String, byte[]> instrumented, List<String> testClasses) {
    int testsJdk = Engine.jdkOf(project.java());
    if (testsJdk < Engine.TestsJdkTooOld.OLDEST) {
      throw new Engine.TestsJdkTooOld(project, testsJdk);
    }
    var refusal = frameworks.refusal();
    if (refusal.isPresent()) {
      throw new Engine.TestsCantRun(project, refusal.get());
    }
    instrumented.forEach(this::stage);
    send("COVER\t" + String.join("\t", testClasses));
  }

  /// The tests couldn't start in the coverage pass: the worker said why ([StartFailure]).
  private Engine.NoTestsRan unstarted(String[] parts) {
    var failure = StartFailure.of(parts[1], parts[2], parts[3], parts[4]);
    return new Engine.NoTestsRan(project, failure, log);
  }

  /// @param listener hears how many tests have run of how many there are, as they run
  Baseline cover(
      Map<String, byte[]> instrumented,
      List<String> testClasses,
      Coverage coverage,
      io.mutastic.api.Listener listener) {
    startCovering(instrumented, testClasses);
    var failed = new Failed();
    var counted = new TestsCounted(coverage, listener);
    while (true) {
      var answer = coverageAnswer(counted::running);
      var parts = answer.split("\t", -1);
      switch (parts[0]) {
        case "TESTS", "STARTED", "TEST", "CASES" -> counted.heard(parts);
        case "LINE" -> coverage.recordLines(parts[1], commaSeparated(parts[2], Integer::valueOf));
        case "LOADING" -> coverage.recordOnlyAtLoad(commaSeparated(parts[1], Integer::valueOf));
        case "VALUE" ->
            coverage.recordValues(
                parts[1], Integer.parseInt(parts[2]), commaSeparated(parts[3], Long::valueOf));
        case "FAILURE" -> failed.heard(parts);
        case "LOADED" -> toWarm.coverageLoaded(Arrays.asList(parts).subList(1, parts.length));
        case "DONE" -> {
          return failed.baseline(parts);
        }
        case "UNSTARTED" -> throw unstarted(parts);
        default -> throw new IllegalStateException("the worker said: " + answer);
      }
    }
  }

  /// The coverage pass's tests as they run, recorded, and counted to a listener as each starts and
  /// finishes: a test the plan didn't list (one made as the tests run) adds to those planned.
  private static final class TestsCounted {
    private final Coverage coverage;
    private final io.mutastic.api.Listener listener;
    private int planned;
    private int done;
    private final java.util.Set<String> started = new java.util.LinkedHashSet<>();

    TestsCounted(Coverage coverage, io.mutastic.api.Listener listener) {
      this.coverage = coverage;
      this.listener = listener;
    }

    /// `TESTS` and how many, `STARTED` and a test, `TEST`, a test and its nanoseconds, or `CASES`,
    /// how many cases ran and were skipped.
    void heard(String[] parts) {
      switch (parts[0]) {
        case "TESTS" -> planned(Integer.parseInt(parts[1]));
        case "CASES" ->
            coverage.recordCases(Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        case "STARTED" -> {
          started.add(parts[1]);
          listener.testStarted(parts[1]);
        }
        default -> {
          started.remove(parts[1]);
          ran(parts[1], Duration.ofNanos(Long.parseLong(parts[2])));
        }
      }
    }

    /// The test started last that hasn't finished, if any.
    Optional<String> running() {
      return started.stream().reduce((earlier, later) -> later);
    }

    void planned(int count) {
      planned = count;
      listener.coverageProgress(done, Math.max(planned, done));
    }

    void ran(String test, Duration took) {
      coverage.recordTest(test, took);
      done++;
      listener.coverageProgress(done, Math.max(planned, done));
    }
  }

  /// What the coverage pass's failing tests failed with, as the worker says it.
  private static final class Failed {
    private final Map<String, String> failures = new java.util.LinkedHashMap<>();
    private final Map<String, String> messages = new java.util.LinkedHashMap<>();
    private final Map<String, TestCase> cases = new java.util.LinkedHashMap<>();

    /// `FAILURE`, the test, its exception's chain, its message and its case.
    void heard(String[] parts) {
      failures.put(parts[1], parts[2]);
      messages.put(parts[1], parts.length > 3 ? parts[3] : "");
      failedCase(parts, 4).ifPresent(failed -> cases.put(parts[1], failed));
    }

    /// `DONE PASSED`, or `DONE FAILED` and the tests that failed: each once, though the worker says
    /// a test once for each of its cases that failed.
    Baseline baseline(String[] done) {
      return new Baseline(
          done[1].equals("PASSED"),
          Arrays.stream(done).skip(2).distinct().toList(),
          failures,
          messages,
          cases);
    }
  }

  /// The worker's next answer in the coverage pass. A pass that outlasts its limit kills the
  /// worker.
  private String coverageAnswer(Supplier<Optional<String>> test) {
    return await(COVERAGE_LIMIT)
        .orElseThrow(
            () -> {
              var current = running;
              boolean stopped = current != null && current.stopped().get();
              kill();
              return new Stopped(stopped, test.get());
            });
  }

  private static <T> Set<T> commaSeparated(String values, Function<String, T> parse) {
    return Arrays.stream(values.split(",", -1)).map(parse).collect(Collectors.toSet());
  }

  /// The input a property test failed on, on one line as its framework says it.
  ///
  /// @param unshrunk whether it's the first that failed, from a framework whose shrinking a
  ///   mutant's run turns off: a run with it on names a smaller one
  record CaughtInput(String text, boolean unshrunk) {}

  /// The outcome of `tests`, run in order until one fails, with `bytes` in place of `className`.
  ///
  /// @param origin where the failure came from: `assertion`, `test`, `code` or `unknown`
  /// @param portClash whether a port in use caused the failure, which with workers side by side can
  ///   be another worker's doing
  /// @param failedCase the case of `failedTest` that failed, when it runs a table of them
  /// @param input the input `failedTest` failed on, when it's a property test that names one
  record Run(
      Status status,
      Optional<String> failedTest,
      Optional<String> failure,
      Optional<String> origin,
      int testsRun,
      boolean portClash,
      Optional<TestCase> failedCase,
      Optional<CaughtInput> input) {

    Run(
        Status status,
        Optional<String> failedTest,
        Optional<String> failure,
        Optional<String> origin,
        int testsRun,
        boolean portClash,
        Optional<TestCase> failedCase) {
      this(status, failedTest, failure, origin, testsRun, portClash, failedCase, Optional.empty());
    }

    Run(Status status, int testsRun) {
      this(status, Optional.empty(), Optional.empty(), Optional.empty(), testsRun, false);
    }

    Run(
        Status status,
        Optional<String> failedTest,
        Optional<String> failure,
        Optional<String> origin,
        int testsRun,
        boolean portClash) {
      this(status, failedTest, failure, origin, testsRun, portClash, Optional.empty());
    }

    /// `test`, the `testsRun`th, ran code that ended the JVM with `status`.
    static Run exited(String test, int status, int testsRun) {
      return new Run(
          status == 0 ? Status.MISSED : Status.CAUGHT,
          Optional.of(test),
          Optional.of("exit: " + status),
          Optional.of("exit"),
          testsRun,
          false);
    }

    /// The same run, its failing test having named `named` as its input.
    Run withInput(Optional<CaughtInput> named) {
      return new Run(status, failedTest, failure, origin, testsRun, portClash, failedCase, named);
    }

    /// Whether a test's code ended the JVM with status 0, which the build reads as a pass.
    boolean exitedAsAPass() {
      return status == Status.MISSED && failedTest.isPresent();
    }
  }

  Run run(String className, byte[] bytes, List<String> tests, Duration timeout) {
    return staged("RUN", () -> stage(className, bytes), tests, timeout);
  }

  /// The same, with every class loaded anew, so the class's static initialiser runs mutated.
  Run runFresh(String className, byte[] bytes, List<String> tests, Duration timeout) {
    return staged("FRESH", () -> stage(className, bytes), tests, timeout);
  }

  /// A test whose code ends the JVM with status 0 hasn't failed: Gradle's test task and cargo test
  /// both pass, though no test after it runs. So it's not a catch, and the tests after it run on a
  /// new worker, as they would in a build that ran them first: `stage` gives it the mutant.
  private Run staged(String command, Runnable stage, List<String> tests, Duration timeout) {
    stage.run();
    var run = result(command, tests, timeout);
    if (!run.exitedAsAPass()) {
      return run;
    }
    var rest = tests.subList(run.testsRun(), tests.size());
    var after = rest.isEmpty() ? new Run(Status.MISSED, 0) : staged(command, stage, rest, timeout);
    return new Run(
        after.status(),
        after.failedTest(),
        after.failure(),
        after.origin(),
        run.testsRun() + after.testsRun(),
        after.portClash(),
        after.failedCase(),
        after.input());
  }

  private Run result(String command, List<String> tests, Duration timeout) {
    exitedDuring = null;
    var answer = testsRun(command + "	" + String.join("	", tests), timeout);
    if (answer.isEmpty()) {
      return ended(tests);
    }
    replaceIfAged();
    return parsed(answer.get());
  }

  /// No answer: the worker ran out of time and is killed, which counts as a timeout, or a test's
  /// code ended its JVM, which the worker said as it went. That's not a hang: it's what that test
  /// saw the mutant do, with the status the JVM ended with. An end the worker didn't say, as a
  /// crash's, or one that didn't finish in time, stays a timeout.
  private Run ended(List<String> tests) {
    var current = running;
    var test = exitedDuring;
    boolean stopped = current != null && current.stopped().get();
    kill();
    if (current == null || test == null || !stopped || current.process().isAlive()) {
      return new Run(Status.TIMED_OUT, tests.size());
    }
    int at = tests.indexOf(test);
    return Run.exited(test, current.process().exitValue(), at < 0 ? tests.size() : at + 1);
  }

  /// `RESULT PASSED <ran>` or `RESULT FAILED <ran> <test> <exception> <origin> <port-clash or ->
  /// <case's index or -> <case's name or -> <shrunk, unshrunk or -> <input>`.
  private static Run parsed(String answer) {
    var parts = answer.split("\t", -1);
    return switch (parts[1]) {
      case "PASSED" -> new Run(Status.MISSED, Integer.parseInt(parts[2]));
      case "FAILED" ->
          new Run(
              Status.CAUGHT,
              Optional.of(parts[3]),
              Optional.of(parts[4]),
              Optional.of(parts[5]),
              Integer.parseInt(parts[2]),
              parts[6].equals("port-clash"),
              failedCase(parts, 7),
              input(parts, 9));
      // A selector that finds nothing: mutastic's own error, which the worker says.
      case "ERROR" -> throw new IllegalStateException(parts.length > 2 ? parts[2] : answer);
      default -> throw new IllegalStateException("the worker said: " + answer);
    };
  }

  /// The case at `at` in a worker's line, its index then its name, unless the index is `-`.
  static Optional<TestCase> failedCase(String[] parts, int at) {
    if (parts.length < at + 2 || parts[at].equals("-")) {
      return Optional.empty();
    }
    return Optional.of(new TestCase(Integer.parseInt(parts[at]), parts[at + 1]));
  }

  /// The input at `at` in a worker's line, how it was drawn then the input, unless that's `-`.
  static Optional<CaughtInput> input(String[] parts, int at) {
    if (parts.length < at + 2 || parts[at].equals("-")) {
      return Optional.empty();
    }
    return Optional.of(new CaughtInput(parts[at + 1], parts[at].equals("unshrunk")));
  }

  /// The next run's property tests shrink a failing input as their framework would: for the
  /// smallest input to name, once the mutant is caught. The mutant stays in place.
  void shrinkNext() {
    send("SHRINK");
  }

  /// How `test` fares on its own with no mutant in, within `timeout`: missed if it passes, caught
  /// with what it failed with if it fails, timed out if it doesn't finish.
  Run unmutated(String test, Duration timeout) {
    var answer = testsRun("RUN\t" + test, timeout);
    if (answer.isEmpty()) {
      kill();
      return new Run(Status.TIMED_OUT, 1);
    }
    replaceIfAged();
    return alone(test, parsed(answer.get()));
  }

  /// How JUnit says it couldn't discover what a selector names (since 5.13).
  private static final String DISCOVERY_ISSUE =
      "org.junit.platform.launcher.core.DiscoveryIssueException";

  /// `run`, of `test` alone, with no mutant in or confirming a catch, unless finding it failed.
  /// Every test already ran once, so a selector that finds nothing, or that JUnit can't discover,
  /// is mutastic's own error: never the test failing on its own, a catch that didn't repeat, nor a
  /// reason to drop its catches.
  static Run alone(String test, Run run) {
    if (run.failure().filter(DISCOVERY_ISSUE::equals).isPresent()) {
      throw new IllegalStateException(
          "mutastic couldn't select " + test + " to run it on its own: JUnit couldn't discover it");
    }
    return run;
  }

  /// The first of `candidates` (one value per constructor parameter of `receiver`, then per method
  /// parameter) where `bytes` changes what the method returns, with both results; empty if none
  /// does in the worker's time budget. `receiver` is how [Access#receiver] says to make it. A call
  /// that never returned on the mutant leaves a thread running, so the worker is replaced.
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
    var answer = testsRun("RUN\t" + test, timeout);
    if (answer.isEmpty()) {
      kill();
      return false;
    }
    replaceIfAged();
    return alone(test, parsed(answer.get())).status() == Status.MISSED;
  }

  /// The answer to `command`, which runs tests, or empty if none came in time. What the tests left
  /// in the worker's temporary directory is deleted once they've answered, so the next tests find
  /// it as these did ([WorkerTemp#deleteAllBut]); a worker that didn't answer is killed, and its
  /// directory goes with it.
  private Optional<String> testsRun(String command, Duration timeout) {
    var current = running();
    var before = current.temp().contents();
    send(command);
    var answer = await(timeout);
    if (answer.isPresent()) {
      current.temp().deleteAllBut(before);
    }
    return answer;
  }

  /// Ends a worker that has aged, between commands, so the next command starts a new one. That's
  /// the worker going on, not a kill, so it isn't counted as another.
  private void replaceIfAged() {
    if (aged && toWarm.canReplaceAWorker()) {
      close();
      workersStarted--;
    }
    aged = false;
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
      WorkerTemp temp,
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
      // It halts on EXIT: a moment to do so before it's stopped, so nothing it writes as it goes
      // is cut short.
      try {
        current.process().waitFor(1, TimeUnit.SECONDS);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      end(current.process(), current.temp(), Process::destroy);
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
      var answer = current.answers().poll(timeout.toNanos(), TimeUnit.NANOSECONDS);
      if (AGED.equals(answer)) {
        aged = true;
        return await(timeout);
      }
      if (answer != null && answer.startsWith(EXITED)) {
        exitedDuring = answer.substring(EXITED.length());
        return await(timeout);
      }
      return Optional.ofNullable(answer).filter(line -> !line.equals(STOPPED));
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      return Optional.empty();
    }
  }

  private void kill() {
    aged = false; // what comes next is new
    var current = running;
    if (current != null) {
      end(current.process(), current.temp(), Process::destroyForcibly);
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

  /// Stops the worker and whatever its tests started and left running, then, once it has gone,
  /// forcibly if it hasn't gone of itself within a few seconds, deletes its temporary directory: a
  /// test it killed mid-way left its own temporary files there.
  ///
  /// What it started is found first: a process whose parent has gone can't be traced to it. A
  /// worker that exits when told does this itself (`Worker`), and so has none left by now. A
  /// process that has detached itself from the worker, as a Gradle daemon or a container under
  /// Docker's own daemon has, isn't among them and is left alone.
  private static void end(Process process, WorkerTemp temp, Consumer<Process> stop) {
    var started = process.descendants().toList();
    stop.accept(process);
    started.forEach(ProcessHandle::destroyForcibly);
    try {
      if (!process.waitFor(5, TimeUnit.SECONDS)) {
        process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    temp.delete();
  }

  /// The running worker, started if none is.
  private Running running() {
    var current = running;
    return current != null ? current : start();
  }

  private Running start() {
    var temp = WorkerTemp.create();
    var process = launch(temp);
    var shutdownHook = endsWithTheEngine(process, temp);
    var started =
        new Running(
            process,
            temp,
            new PrintStream(process.getOutputStream(), false, UTF_8),
            new LinkedBlockingQueue<>(),
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
    return warmed(started);
  }

  /// The worker, with the classes the coverage pass loaded initialised; if it can't do that, a new
  /// one that doesn't try, as a class that can't be initialised stays that way ([ClassesToWarm]).
  private Running warmed(Running started) {
    var classes = toWarm.classes();
    if (classes.isEmpty()) {
      return started;
    }
    send("WARM\t" + String.join("\t", classes));
    if (await(STARTUP).filter(answer -> answer.equals("WARMED")).isPresent()) {
      return started;
    }
    toWarm.refuse();
    kill();
    workersStarted--;
    return running();
  }

  /// The worker first, then the project's resources ahead of its libraries, as the build has them
  /// ([ResourceCopy]), then the project's test classpath, then what mutastic brings for its tests
  /// to run on the JUnit Platform, when they need it ([TestFrameworks]); for a framework that loads
  /// the app itself, the project's own directories last, where it finds them, and no copy.
  private String classpath() {
    boolean framework = frameworks.loadsTheApp();
    var own =
        framework
            ? Stream.of(
                    project.classes(),
                    project.alongside(),
                    project.resources(),
                    project.testClasses(),
                    project.testResources())
                .flatMap(List::stream)
            : Stream.<Path>empty();
    return join(
        Stream.of(
                Stream.of(workerJar),
                framework ? Stream.<Path>empty() : Stream.of(resources),
                project.dependencies().stream(),
                frameworks.support().stream(),
                own)
            .flatMap(each -> each));
  }

  /// For mutastic's own tests: the coverage agent its build measures the worker with, as the worker
  /// runs in a JVM of its own. Nothing otherwise.
  private static List<String> measured() {
    var agent = System.getProperty("mutastic.workerJvmArgs", "");
    // Each worker writes a file of its own: side by side, some of them killed, they can't share.
    return agent.isBlank()
        ? List.of()
        : List.of(agent.replace("{worker}", java.util.UUID.randomUUID().toString()));
  }

  /// The worker's system class loader, so the JDK's own service lookups find the project as they do
  /// in the build; not in framework mode, where the framework loads the app.
  private List<String> systemLoader() {
    return frameworks.loadsTheApp()
        ? List.of()
        : List.of(
            "-Djava.system.class.loader=io.mutastic.worker.SystemLoader",
            // The JVM warns on stdout, the worker's protocol, that a system class loader of its
            // own turns off its archive of application classes.
            "-Xlog:cds=off");
  }

  /// Framework mode, for tests run in a framework that loads the app itself.
  private List<String> mode() {
    return frameworks.loadsTheApp() ? List.of("--framework") : List.of();
  }

  /// However the engine exits, its worker goes too, and what the worker leaves.
  private static Thread endsWithTheEngine(Process process, WorkerTemp temp) {
    var hook =
        new Thread(
            () -> {
              end(process, temp, Process::destroyForcibly);
            });
    Runtime.getRuntime().addShutdownHook(hook);
    return hook;
  }

  /// The worker's command line: the project's own JDK and JVM arguments, with the worker as agent,
  /// and `temp` as its temporary directory unless the project's arguments name one.
  private List<String> command(WorkerTemp temp) {
    var command = new ArrayList<String>();
    command.add(project.java().toString());
    command.add(temp.jvmArg());
    command.addAll(project.jvmArgs());
    // After the project's own, so every framework draws from the one seed.
    PropertyTests.of(project).ifPresent(properties -> command.addAll(properties.jvmArgs()));
    command.addAll(measured());
    command.addAll(systemLoader());
    command.add("-javaagent:" + workerJar);
    command.addAll(List.of("-cp", classpath(), "io.mutastic.worker.Worker"));
    command.addAll(workerArgs());
    command.addAll(mode());
    return command;
  }

  /// What the worker is told of the project: its code, its tests, and the tags it runs.
  private List<String> workerArgs() {
    return List.of(
        "--production="
            + join(
                Stream.of(project.classes(), project.alongside(), project.resources())
                    .flatMap(List::stream)),
        "--tests="
            + join(Stream.concat(project.testClasses().stream(), project.testResources().stream())),
        "--resource-copy=" + resources,
        "--include-tags=" + String.join(",", project.tests().includeTags()),
        "--exclude-tags=" + String.join(",", project.tests().excludeTags()),
        "--exclude-methods=" + String.join(",", methodsLeftOut));
  }

  private Process launch(WorkerTemp temp) {
    try {
      var builder =
          new ProcessBuilder(command(temp)).directory(project.workingDirectory().toFile());
      // The environment the build's tests get: its JAVA_HOME, not mutastic's, then the task's own.
      BuildJava.restore(builder.environment());
      builder.environment().putAll(project.environment());
      return builder.redirectError(ProcessBuilder.Redirect.appendTo(log.toFile())).start();
    } catch (IOException e) {
      temp.delete();
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
