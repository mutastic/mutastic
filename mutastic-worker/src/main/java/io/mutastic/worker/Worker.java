package io.mutastic.worker;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectClass;
import static org.junit.platform.engine.discovery.DiscoverySelectors.selectMethod;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import org.junit.platform.commons.JUnitException;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.discovery.MethodSelector;
import org.junit.platform.engine.support.descriptor.ClassSource;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.LauncherDiscoveryRequest;
import org.junit.platform.launcher.TagFilter;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/// Runs the project's tests on the project's JDK, for an engine on a newer one. The engine sends
/// finished classes (instrumented or mutated) and the tests to run; the worker loads them, runs the
/// JUnit Platform, and answers on stdout. Test output goes to stderr, so a test that prints can't
/// corrupt the protocol.
///
/// Arguments: `--production=<dirs>` (main classes and resources) and `--tests=<dirs>` (test classes
/// and resources), each separated by the platform's path separator; `--resource-copy=<dir>`, the
/// engine's copy of the project's resources on the classpath ([ResourceCopy]).
///
/// Protocol, one command per line, fields separated by tabs (test ids can contain spaces:
/// `#play(java.lang.String, int)`):
///
/// | Engine sends | Worker answers |
/// |---|---|
/// | `CLASS <name> <base64>`: use these bytes for the next command | nothing |
/// | `COVER <test>...`: run every test, recording lines and arguments | `TESTS <count>` as they start, `TEST <id> <nanos>`, `LINE <id> <ids>` and `VALUE <id> <parameter> <values>` lines, then `DONE PASSED` or `DONE FAILED <id>...` |
/// | `COVER` answers also hold `LOADING <ids>`: the lines some test ran, but only while a class was loading, and `LOADED <class>...`: the production classes it loaded, in order | |
/// | `WARM <class>...`: load and initialise them, with no mutant in, as a worker that didn't run `COVER` must before its first mutant | `WARMED`, or `NOT-WARMED <class>` for the first that couldn't be |
/// | `RUN <test>...`: run in order until one fails, property tests unshrunk ([Shrinking]) | `RESULT PASSED <ran>`, `RESULT FAILED <ran> <test> <exception> <origin> <port-clash or -> <case> <input>` (the case as [FailedCase] says it, the input as [CaughtInput] does) or `RESULT ERROR <why>`, after `AGED` if this worker is now worth replacing ([Aging]) |
/// | `SHRINK`: the next `RUN` or `FRESH` shrinks a failing property's input, as the framework would, leaving the mutant in place | nothing |
/// | `FRESH <test>...`: the same, with every class loaded anew, so static initialisers run with the staged classes | as for `RUN` |
/// | `SUGGEST <class> <method> <descriptor> <base64> <inputs> <receiver>`: find inputs where a mutant changes the result; inputs are `1;2\|3;4`, `-` for none; the receiver is `-` for a static method, else a constructor descriptor whose arguments start each input, or for an inner class its outer class's receiver, `>`, then its own | `SUGGESTED <inputs> <today> <with mutant> <stuck or ->`, or `SUGGESTED -` |
/// | `COVER` answers `UNSTARTED <exception> <message> <cause> <cause's message>` in place of `DONE` when the tests can't start: no engine runs them, or an engine fails to find them | |
/// | `EXIT` | nothing; the worker exits |
/// | a test ends the JVM (`System.exit`) during `RUN` or `FRESH` | `EXITED <test>` as it goes, in place of `RESULT`; the engine reads the status from the process |
///
/// A test is `com.example.PricingTest#method` or a class name. The engine kills the worker if it
/// doesn't answer in time: a mutant that loops forever can't be stopped any other way.
public final class Worker {
  /// What a new worker costs, measured on mutastic's own engine: see [Aging]. A property so that a
  /// test can have a worker replaced after every mutant.
  private static final long WORTH_A_NEW_WORKER =
      Duration.ofMillis(Long.getLong("mutastic.worthANewWorkerMillis", 15_000)).toNanos();

  private final List<Path> productionRoots;
  private final List<Path> testRoots;
  private final Tags tags;
  private final TestSplit splitTests;
  private final Set<String> testClassNames;
  private final PrintStream protocol;

  /// The context class loader of every run, for the worker's life ([ContextLoader]).
  private final ContextLoader context = new ContextLoader(ClassLoader.getSystemClassLoader());

  private final Map<String, byte[]> staged = new HashMap<>();
  private final MutantInPlace inPlace = new MutantInPlace();
  private final Aging aging = new Aging(WORTH_A_NEW_WORKER);

  /// Made by the coverage pass, or by the first command a new worker gets after one was killed.
  private @Nullable ProductionLoader production;

  /// For tests run in a framework that loads the app itself (`--framework`): see [Framework].
  private final @Nullable Framework framework;

  private int runs;

  /// The test `RUN` or `FRESH` is running, if one is: what a test that ends the JVM was running.
  private volatile @Nullable String running;

  private String lastFailure = "unknown";
  private String lastOrigin = "unknown";
  private boolean lastPortClash;

  /// The case the first failure of the last `RUN`'s test was, if it was one: see [FailedCase].
  private String firstCase = FailedCase.NONE;

  /// The input the first failure of the last `RUN`'s test names, if it's a property's: see
  /// [CaughtInput].
  private String firstInput = CaughtInput.NONE;

  private final Shrinking shrinking = new Shrinking();

  /// Whether the next `RUN` or `FRESH` shrinks what a failing property names (`SHRINK`).
  private boolean shrinkNext;

  /// Each failing test's exception and its causes, `java.io.UncheckedIOException>java.net
  /// .ConnectException`: for the coverage pass's `FAILURE` lines, which say why a test that then
  /// passed was flaky.
  private final Map<String, String> failures = new java.util.LinkedHashMap<>();

  /// Each failing test's message, its first line: what the assertion said, for a test that fails
  /// with no mutant in.
  private final Map<String, String> messages = new java.util.LinkedHashMap<>();

  /// Each failing test's case, the one it failed in, when it runs a table of them: see
  /// [FailedCase].
  private final Map<String, String> failedCases = new java.util.LinkedHashMap<>();

  /// The JUnit tags the build's test task runs by, and the test methods the run leaves out: every
  /// run here is filtered the same way.
  record Tags(List<String> include, List<String> exclude, LeftOutMethods leftOut) {
    static Tags of(String[] args) {
      return new Tags(
          values(args, "--include-tags="),
          values(args, "--exclude-tags="),
          new LeftOutMethods(methods(args, "--exclude-methods=")));
    }

    /// The methods after `flag`, split as [TestNames#split] splits them: an overload's parameter
    /// types have commas of their own.
    private static List<String> methods(String[] args, String flag) {
      return Arrays.stream(args)
          .filter(arg -> arg.startsWith(flag))
          .flatMap(arg -> TestNames.split(arg.substring(flag.length())).stream())
          .toList();
    }

    void filter(LauncherDiscoveryRequestBuilder request) {
      if (!include.isEmpty()) {
        request.filters(TagFilter.includeTags(include));
      }
      if (!exclude.isEmpty()) {
        request.filters(TagFilter.excludeTags(exclude));
      }
      if (!leftOut.methods().isEmpty()) {
        request.filters(leftOut);
      }
    }

    private static List<String> values(String[] args, String flag) {
      return Arrays.stream(args)
          .filter(arg -> arg.startsWith(flag))
          .flatMap(arg -> Arrays.stream(arg.substring(flag.length()).split(",", -1)))
          .filter(tag -> !tag.isBlank())
          .toList();
    }
  }

  private Worker(
      List<Path> productionRoots,
      List<Path> testRoots,
      Tags tags,
      PrintStream protocol,
      boolean framework)
      throws IOException {
    this.productionRoots = productionRoots;
    this.testRoots = testRoots;
    this.tags = tags;
    this.protocol = protocol;
    var allRoots = concat(testRoots, productionRoots);
    var scanned = new Scannable(Scannable.SPRING, allRoots, ClassLoader.getSystemClassLoader());
    this.splitTests =
        TestSplit.of(
            splitTests(productionRoots, testRoots),
            union(TestSplit.providersIn(allRoots), TestSplit.sharedByTheEngine()),
            scanned);
    this.testClassNames = classNamesIn(testRoots);
    this.framework =
        framework ? new Framework(productionRoots, classNamesIn(productionRoots)) : null;
  }

  public static void main(String[] args) throws Exception {
    var protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF_8);
    System.setOut(System.err);
    ResourceCopy.at(roots(args, "--resource-copy=").stream().findFirst().orElse(null));
    boolean framework = Arrays.asList(args).contains("--framework");
    if (!framework) {
      System.setProperty(
          "java.class.path", BuildClassPath.of(args, System.getProperty("java.class.path", "")));
    }
    var worker =
        new Worker(
            roots(args, "--production="),
            roots(args, "--tests="),
            Tags.of(args),
            protocol,
            framework);
    var commands = readCommands();
    sayWhatExits(worker, protocol);
    protocol.println("READY\t" + Runtime.version().feature());
    for (String line = commands.take(); !line.equals("EXIT"); line = commands.take()) {
      worker.handle(line.split("\t", -1));
    }
    writeCoverage();
    haltWithWhatTestsStarted(); // even if a test left non-daemon threads behind
  }

  /// When code a test runs ends the JVM (`System.exit`, which runs the shutdown hooks), says which
  /// test it was, as its run won't finish: the exit is the mutant's doing, seen by that test, and
  /// the engine tells it from a worker that hangs. The worker's own ends halt, and say nothing.
  private static void sayWhatExits(Worker worker, PrintStream protocol) {
    Runtime.getRuntime()
        .addShutdownHook(
            new Thread(
                () -> {
                  var test = worker.running;
                  if (test != null) {
                    protocol.println("EXITED\t" + test);
                  }
                },
                "mutastic-exit"));
  }

  /// Ends what the tests started and left running, then the worker. Once the worker has gone, a
  /// process it started can't be traced to it, so nothing else could. A process that has detached
  /// itself, as a Gradle daemon or a container under Docker's own daemon has, isn't among them and
  /// is left alone.
  private static void haltWithWhatTestsStarted() {
    try {
      ProcessHandle.current().descendants().forEach(ProcessHandle::destroyForcibly);
    } finally {
      Runtime.getRuntime().halt(0);
    }
  }

  /// When a coverage agent is attached, as when mutastic's own build measures the worker, writes
  /// what it has: halting skips the shutdown hook that would. Nothing otherwise.
  private static void writeCoverage() {
    try {
      var agent = Class.forName("org.jacoco.agent.rt.RT").getMethod("getAgent").invoke(null);
      agent.getClass().getMethod("dump", boolean.class).invoke(agent, false);
    } catch (ReflectiveOperationException | LinkageError e) {
      // no agent: nothing to write
    }
  }

  private void handle(String[] command) throws Exception {
    var tests = Arrays.asList(command).subList(1, command.length);
    if (!keepsTheMutant(command[0]) && production != null) {
      inPlace.restore(production);
    }
    switch (command[0]) {
      case "CLASS" -> staged.put(command[1], Base64.getDecoder().decode(command[2]));
      case "COVER" -> coverInstrumented(tests);
      case "WARM" -> warm(tests);
      case "RUN" -> runWithStaged(tests);
      case "FRESH" -> runFresh(tests);
      case "SUGGEST" -> suggest(command);
      case "SHRINK" -> shrinkNext = true;
      default -> protocol.println("RESULT\tERROR\tunknown command " + command[0]);
    }
  }

  /// Whether `command` leaves the mutant in place for the next: staging one, running against it, or
  /// saying how the next run shrinks.
  private static boolean keepsTheMutant(String command) {
    return command.equals("CLASS") || command.equals("RUN") || command.equals("SHRINK");
  }

  /// Covers with the staged classes, instrumented, in a new production loader that later commands
  /// reuse.
  private void coverInstrumented(List<String> tests) throws Exception {
    if (framework != null) {
      framework.swapIn(staged);
      try {
        cover(tests);
      } finally {
        framework.swapBack(staged.keySet());
        staged.clear();
      }
      return;
    }
    var instrumented =
        new ProductionLoader(
            productionRoots, staged, testRoots, splitTests, ClassLoader.getSystemClassLoader());
    production = instrumented;
    SystemLoader.project(instrumented);
    cover(tests);
    instrumented.restoreAll();
    staged.clear();
  }

  /// Initialises the classes in order, as the coverage pass's tests did, with the build's bytes: a
  /// mutant that reaches a static initialiser after this can't break its class.
  private void warm(List<String> classes) {
    var loader = production();
    var thread = Thread.currentThread();
    var previous = thread.getContextClassLoader();
    thread.setContextClassLoader(loader);
    try {
      for (String name : classes) {
        try {
          Class.forName(name, true, loader);
        } catch (ClassNotFoundException | LinkageError e) {
          e.printStackTrace(System.err);
          protocol.println("NOT-WARMED\t" + name);
          return;
        }
      }
      protocol.println("WARMED");
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  /// Runs with the staged classes (a mutant) swapped in, in place of the last mutant.
  private void runWithStaged(List<String> tests) throws Exception {
    if (framework != null) {
      runInTheFramework(framework, tests);
      return;
    }
    var loader = production();
    long swapStarted = System.nanoTime();
    inPlace.put(loader, staged);
    if (aging.swapTook(System.nanoTime() - swapStarted)) {
      System.err.println("AGED");
      protocol.println("AGED");
    }
    staged.clear();
    run(tests, loader);
  }

  /// Runs with the staged classes in a production loader of their own, discarded after: a mutant
  /// that changes what a static initialiser does needs its class loaded anew. A framework loads the
  /// app itself, so there the mutant is swapped in as usual.
  private void runFresh(List<String> tests) throws Exception {
    if (framework != null) {
      runInTheFramework(framework, tests);
      return;
    }
    var fresh =
        new ProductionLoader(
            productionRoots, staged, testRoots, splitTests, ClassLoader.getSystemClassLoader());
    staged.clear();
    // A library finds the project's classes by name through the system loader: it must find this
    // run's, or a proxy it makes of one won't be the class the test asks for.
    SystemLoader.project(fresh);
    try {
      run(tests, fresh);
    } finally {
      SystemLoader.project(production);
    }
  }

  /// The same, with the staged classes swapped into the framework's copies of them.
  private void runInTheFramework(Framework framework, List<String> tests) throws Exception {
    try {
      framework.swapIn(staged);
      run(tests, production());
    } catch (IllegalStateException e) {
      protocol.println("RESULT\tERROR\t" + e.getMessage());
    } finally {
      framework.swapBack(staged.keySet());
      staged.clear();
    }
  }

  private void suggest(String[] command) throws Exception {
    var candidates = candidates(command[5]);
    var target = new Suggester.Target(command[1], command[2], command[3], command[6]);
    var found =
        Suggester.find(production(), target, Base64.getDecoder().decode(command[4]), candidates);
    protocol.println(suggested(found));
  }

  /// `1;2|3;4` → `[[1, 2], [3, 4]]`; `-` is an input with no values.
  static List<List<Long>> candidates(String encoded) {
    return Arrays.stream(encoded.split("\\|", -1))
        .map(
            inputs ->
                inputs.equals("-")
                    ? List.<Long>of()
                    : Arrays.stream(inputs.split(";", -1)).map(Long::valueOf).toList())
        .toList();
  }

  /// `[1, 2]` → `1;2`, and `-` for no values: a method with no parameters on a receiver made with
  /// no arguments.
  static String encoded(List<Long> inputs) {
    return inputs.isEmpty()
        ? "-"
        : inputs.stream().map(String::valueOf).collect(Collectors.joining(";"));
  }

  private static String suggested(Suggester.Found found) {
    return found
        .inputs()
        .map(
            inputs ->
                "SUGGESTED\t"
                    + encoded(inputs)
                    + "\t"
                    + found.today()
                    + "\t"
                    + found.mutated()
                    + "\t"
                    + (found.stuck() ? "stuck" : "-"))
        .orElse("SUGGESTED\t-");
  }

  /// The production loader: the coverage pass's, or a new one for a worker started after the last
  /// one was killed, which hasn't had one.
  private ProductionLoader production() {
    if (production == null) {
      production =
          new ProductionLoader(
              productionRoots, Map.of(), testRoots, splitTests, ClassLoader.getSystemClassLoader());
      SystemLoader.project(production);
    }
    return production;
  }

  private void cover(List<String> tests) {
    shrinking.set(true);
    Probes.reset();
    failures.clear();
    messages.clear();
    failedCases.clear();
    var scopes = new TestScopes(Worker::id, framework == null);
    var planned = new PlannedTests(Worker::id, count -> protocol.println("TESTS\t" + count));
    var starting = new TestsStarting(Worker::id, test -> protocol.println("STARTED\t" + test));
    var cases = new TestCases();
    List<String> failing;
    try {
      failing = execute(tests, production(), planned, starting, timingTests(), scopes, cases);
    } catch (RuntimeException | LinkageError e) {
      unstarted(e);
      return;
    }
    reportCoverage(scopes, cases, failing);
  }

  /// What the coverage pass recorded, ending in `DONE` and the tests that failed.
  private void reportCoverage(TestScopes scopes, TestCases cases, List<String> failing) {
    Probes.flush();
    scopes.credit();
    reportLines();
    reportOnlyAtLoad();
    reportValues();
    reportLoaded();
    reportFailures();
    reportLeftOut();
    protocol.println("CASES\t" + cases.ran() + "\t" + cases.skipped());
    protocol.println(
        "DONE\t" + (failing.isEmpty() ? "PASSED" : "FAILED\t" + String.join("\t", failing)));
  }

  /// The tests couldn't start: the platform found no engine for them, or an engine failed to find
  /// them, as TestNG's does on a TestNG older than it runs. Said as `UNSTARTED`, the exception, its
  /// message, and its first cause's class and message (`-` for none), so the engine names the cause
  /// rather than a JVM that stopped.
  private void unstarted(Throwable thrown) {
    thrown.printStackTrace(System.err);
    var cause = thrown.getCause();
    protocol.println(
        "UNSTARTED\t"
            + thrown.getClass().getName()
            + "\t"
            + message(thrown)
            + "\t"
            + (cause == null ? "-" : cause.getClass().getName())
            + "\t"
            + (cause == null ? "-" : message(cause)));
  }

  /// `LEFT-OUT`, each test the run's names left out of the coverage pass, as [TestNames#id] names
  /// it: so the engine can say of a name that left out none.
  private void reportLeftOut() {
    tags.leftOut().leftOut().forEach(test -> protocol.println("LEFT-OUT\t" + test));
  }

  /// `FAILURE`, each failing test, its exception's chain, its message and its case.
  private void reportFailures() {
    failures.forEach(
        (test, chain) ->
            protocol.println(
                "FAILURE\t"
                    + test
                    + "\t"
                    + chain
                    + "\t"
                    + messages.getOrDefault(test, "")
                    + "\t"
                    + failedCases.getOrDefault(test, FailedCase.NONE)));
  }

  /// Answers `TEST` with how long each test takes alone (see [TestTimes]).
  private TestExecutionListener timingTests() {
    return new TestTimes(
        Worker::id, (test, nanos) -> protocol.println("TEST\t" + test + "\t" + nanos));
  }

  private void reportLines() {
    Probes.linesByTest()
        .forEach(
            (test, lines) -> {
              if (!lines.isEmpty()) {
                protocol.println(
                    "LINE\t"
                        + test
                        + "\t"
                        + lines.stream().map(String::valueOf).collect(Collectors.joining(",")));
              }
            });
  }

  private void reportOnlyAtLoad() {
    var lines = Probes.onlyAtLoad();
    if (!lines.isEmpty()) {
      protocol.println(
          "LOADING\t" + lines.stream().map(String::valueOf).collect(Collectors.joining(",")));
    }
  }

  private void reportLoaded() {
    if (framework == null) {
      protocol.println("LOADED\t" + String.join("\t", production().loadedFromTheBuild()));
    }
  }

  private void reportValues() {
    Probes.valuesByTest()
        .forEach(
            (test, byParameter) ->
                byParameter.forEach(
                    (parameter, values) ->
                        protocol.println(
                            "VALUE\t"
                                + test
                                + "\t"
                                + parameter
                                + "\t"
                                + values.stream()
                                    .sorted()
                                    .map(String::valueOf)
                                    .collect(Collectors.joining(",")))));
  }

  /// The engine orders tests fastest first; the worker stops at the first failure.
  private void run(List<String> tests, ProductionLoader loader) {
    shrinkingAsAsked();
    int ran = 0;
    for (String test : tests) {
      ran++;
      var ending = runOne(test, ran, loader);
      if (ending != null) {
        protocol.println(ending);
        return;
      }
    }
    protocol.println("RESULT\tPASSED\t" + ran);
  }

  /// The `RESULT` line that ends a `RUN` at `test`, the `ran`th of its tests: a failure, or a
  /// selector that ran nothing; null if it passed.
  private @Nullable String runOne(String test, int ran, ProductionLoader loader) {
    var started = new StartedTests();
    List<String> failing;
    try {
      failing = executeSaid(test, loader, started);
    } catch (Unselectable e) {
      e.printStackTrace(System.err);
      return "RESULT\tERROR\t" + e.getMessage();
    } catch (RuntimeException e) {
      // Finding the tests failed: TestNG makes the test classes as it finds them, so a mutant
      // that breaks a test class's constructor breaks it here, before any test can fail.
      remember(e);
      return failedResult(ran, test);
    }
    // A selector that matches nothing runs nothing and fails nothing: that's not a pass.
    if (started.count == 0 && failing.isEmpty()) {
      return "RESULT\tERROR\tmutastic selected " + test + ", and no test ran";
    }
    return failing.isEmpty() ? null : failedResult(ran, test);
  }

  /// Property tests unshrunk for this run, unless `SHRINK` asked for it once.
  private void shrinkingAsAsked() {
    shrinking.set(shrinkNext);
    shrinkNext = false;
  }

  /// One test of a `RUN`, said to be running while it runs, for if its code ends the JVM.
  private List<String> executeSaid(String test, ProductionLoader loader, StartedTests started) {
    firstCase = FailedCase.NONE;
    firstInput = CaughtInput.NONE;
    running = test;
    try {
      return execute(List.of(test), loader, started);
    } finally {
      running = null;
    }
  }

  /// Counts the tests that started, not the containers.
  private static final class StartedTests implements TestExecutionListener {
    private int count;

    @Override
    public void executionStarted(TestIdentifier identifier) {
      if (identifier.isTest()) {
        count++;
      }
    }
  }

  private String failedResult(int ran, String test) {
    return "RESULT\tFAILED\t"
        + ran
        + "\t"
        + test
        + "\t"
        + lastFailure
        + "\t"
        + lastOrigin
        + "\t"
        + (lastPortClash ? "port-clash" : "-")
        + "\t"
        + firstCase
        + "\t"
        + firstInput;
  }

  private List<String> execute(
      List<String> tests, ProductionLoader production, TestExecutionListener... observers) {
    var listeners = new ArrayList<TestExecutionListener>(List.of(observers));
    if (framework != null) {
      // By name: the framework's loader must load the test classes itself.
      var failing = new ArrayList<String>();
      listeners.add(recordingFailures(failing));
      framework.launcher().execute(byName(tests), listeners.toArray(TestExecutionListener[]::new));
      return failing;
    }
    var loader = new TestLoader(testRoots, production, ++runs);
    var thread = Thread.currentThread();
    var previous = thread.getContextClassLoader();
    context.runWith(loader);
    thread.setContextClassLoader(context);
    try {
      var request = request(loader, tests);
      var failing = new ArrayList<String>();
      listeners.add(recordingFailures(failing));
      LauncherFactory.create().execute(request, listeners.toArray(TestExecutionListener[]::new));
      return failing;
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  /// Selects each test, a class or a method, by name.
  private LauncherDiscoveryRequest byName(List<String> tests) {
    var request = LauncherDiscoveryRequestBuilder.request();
    tags.filter(request);
    for (String test : tests) {
      int hash = test.indexOf('#');
      request.selectors(
          hash < 0
              ? selectClass(test)
              : selectMethod(test.substring(0, hash), method(test), parameters(test)));
    }
    return request.build();
  }

  /// Selects each test, a class or a method, as loaded for this run.
  private LauncherDiscoveryRequest request(TestLoader loader, List<String> tests)
      throws ClassNotFoundException {
    var request = LauncherDiscoveryRequestBuilder.request();
    tags.filter(request);
    for (String test : tests) {
      int hash = test.indexOf('#');
      request.selectors(
          hash < 0
              ? selectClass(loader.loadClass(loader.copyOf(test)))
              : methodOfCopy(loader, test, hash));
    }
    return request.build();
  }

  /// The method `test` names, on this run's copy of its class, by the types it takes as the copy
  /// declares them. It's looked up here, so a selector that finds no method is said as what it is:
  /// mutastic's own error, not a failure of the test.
  private static MethodSelector methodOfCopy(TestLoader loader, String test, int hash)
      throws ClassNotFoundException {
    var selector =
        selectMethod(
            loader.loadClass(loader.copyOf(test.substring(0, hash))),
            method(test),
            loader.parametersOfCopy(parameters(test)));
    try {
      selector.getJavaMethod();
    } catch (JUnitException e) {
      throw new Unselectable(test, e);
    }
    return selector;
  }

  /// A test mutastic couldn't select again by the id it recorded: its own error, never the test's.
  private static final class Unselectable extends RuntimeException {
    private static final long serialVersionUID = 1L;

    Unselectable(String test, Throwable cause) {
      super("mutastic couldn't select " + test + ": " + message(cause), cause);
    }
  }

  /// Adds each failure's id to `failing` and keeps the last one's exception, origin and port clash
  /// for the `RESULT` line, with the case of the first. A test of many cases is named with the
  /// first that failed, and its message: the lowest row that shows the mutant.
  private TestExecutionListener recordingFailures(List<String> failing) {
    return new TestExecutionListener() {
      @Override
      public void executionFinished(TestIdentifier test, TestExecutionResult result) {
        // Containers too: a mutant that breaks a test class's set-up (a static initialiser,
        // `@BeforeAll`) fails the class, and its tests never start. A case an assumption aborts
        // didn't fail: it checked nothing, and is counted as skipped ([TestCases]).
        if (result.getStatus() != TestExecutionResult.Status.FAILED) {
          return;
        }
        if (result.getThrowable().filter(DuplicateClass::in).isPresent()) {
          unjudged(test, result);
        } else {
          failed(test, result, failing);
        }
      }
    };
  }

  /// Records a failure of `test`: in `failing`, and what the `RESULT` line and the log say of it.
  private void failed(TestIdentifier test, TestExecutionResult result, List<String> failing) {
    var failedCase = FailedCase.of(test);
    if (failing.isEmpty()) {
      firstFailure(failedCase, result);
    }
    failing.add(id(test));
    lastFailure = "unknown";
    lastOrigin = "unknown";
    lastPortClash = false;
    result.getThrowable().ifPresent(this::remember);
    failedCases.putIfAbsent(id(test), failedCase);
    result.getThrowable().ifPresent(thrown -> failures.put(id(test), causes(thrown)));
    result.getThrowable().ifPresent(thrown -> messages.putIfAbsent(id(test), message(thrown)));
    System.err.println("FAILED\t" + id(test));
    result.getThrowable().ifPresent(thrown -> thrown.printStackTrace(System.err));
  }

  /// A test that failed only on a class the JVM refused as defined twice ([DuplicateClass]): not a
  /// failure, so the run goes on to the next test, and said to the engine, which names it.
  private void unjudged(TestIdentifier test, TestExecutionResult result) {
    protocol.println("UNJUDGED\t" + id(test));
    System.err.println("UNJUDGED\t" + id(test));
    result.getThrowable().ifPresent(thrown -> thrown.printStackTrace(System.err));
  }

  /// The case and the input the first failure of a test names, for its `RESULT` line.
  private void firstFailure(String failedCase, TestExecutionResult result) {
    firstCase = failedCase;
    firstInput =
        result
            .getThrowable()
            .map(thrown -> CaughtInput.of(thrown, shrinking.off()))
            .orElse(CaughtInput.NONE);
  }

  /// What the `RESULT` line says of a failure: its exception, origin and port clash.
  private void remember(Throwable thrown) {
    lastFailure = thrown.getClass().getName();
    lastOrigin = origin(thrown);
    lastPortClash = portClash(thrown);
  }

  /// What a failure says, on one line: the first line of its message, else its class's name. A
  /// first line that ends in a colon says what follows it, so the lines after it come too: JUnit
  /// 4's `Invalid test class 'a.BTest':` and then why. A test class's copy is named as the build
  /// names it.
  static String message(Throwable thrown) {
    var said = thrown.getMessage() == null ? "" : thrown.getMessage();
    var lines = said.lines().map(String::strip).filter(line -> !line.isEmpty()).toList();
    var line =
        lines.isEmpty()
            ? thrown.getClass().getName()
            : lines.get(0).endsWith(":") ? String.join(" ", lines) : lines.get(0);
    line = Renamer.original(line).replace('\t', ' ');
    return line.substring(0, Math.min(line.length(), MESSAGE));
  }

  /// The most of a failure's message the protocol carries.
  private static final int MESSAGE = 300;

  /// The exception and its causes, outermost first, by class: each once, so a chain that loops back
  /// on itself still ends.
  private static String causes(Throwable thrown) {
    var visited =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    var names = new ArrayList<String>();
    for (Throwable cause = thrown; cause != null && visited.add(cause); cause = cause.getCause()) {
      names.add(cause.getClass().getName());
    }
    return String.join(">", names);
  }

  /// Whether a port in use caused it, anywhere in its causes: with workers running side by side,
  /// that can be another worker's test holding the port, not the mutant. Each cause is visited
  /// once, so a chain that loops back on itself still ends.
  private static boolean portClash(Throwable thrown) {
    var visited =
        java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
    for (Throwable cause = thrown; cause != null && visited.add(cause); cause = cause.getCause()) {
      if (cause instanceof java.net.BindException) {
        return true;
      }
      for (Throwable suppressed : cause.getSuppressed()) {
        if (suppressed instanceof java.net.BindException) {
          return true;
        }
      }
    }
    return false;
  }

  /// Where a failure came from: `assertion` (a check failed), `test` (the test's own code threw,
  /// like a null it didn't expect) or `code` (the code under test threw).
  private String origin(Throwable thrown) {
    if (thrown instanceof AssertionError) {
      return "assertion";
    }
    for (StackTraceElement frame : thrown.getStackTrace()) {
      var name = Renamer.original(frame.getClassName());
      int dollar = name.indexOf('$');
      if (testClassNames.contains(dollar < 0 ? name : name.substring(0, dollar))
          || testClassNames.contains(name)) {
        return "test";
      }
      if (production().classFile(name) != null) {
        return "code";
      }
    }
    return "unknown";
  }

  /// `com.example.PricingTest#discount(java.nio.file.Path)`, under the name the build gave the
  /// class. Parameter types too: `selectMethod(class, "name")` finds only a no-argument method, so
  /// a test taking a `@TempDir Path` would select nothing. A test class, which fails as a whole
  /// when its setup or teardown does, is `com.example.PricingTest`, which selects it again; so is a
  /// test with no source that a JUnit 4 runner reports for its class, as `initializationError` is
  /// for a class JUnit 4 can't run. Anything else is the platform's unique id.
  static String id(TestIdentifier test) {
    var testClass =
        test.getSource().filter(ClassSource.class::isInstance).map(ClassSource.class::cast);
    if (test.isContainer() && testClass.isPresent()) {
      return Renamer.original(testClass.get().getClassName());
    }
    return Renamer.original(
        test.getSource()
            .filter(MethodSource.class::isInstance)
            .map(MethodSource.class::cast)
            .map(
                method ->
                    TestNames.id(
                        method.getClassName(),
                        method.getMethodName(),
                        method.getMethodParameterTypes()))
            .or(() -> runnerClass(test))
            .orElse(test.getUniqueId()));
  }

  /// `[runner:com.example.FooTest]`, the vintage engine's runner for a JUnit 4 class, in the unique
  /// id of a test with no method: the class it runs, unless the test has a method of its own. JUnit
  /// 4 finds the class by name with its own loader, so the source is the class when that loader
  /// sees the project's and none when it doesn't.
  private static java.util.Optional<String> runnerClass(TestIdentifier test) {
    if (test.getSource().filter(source -> !(source instanceof ClassSource)).isPresent()) {
      return java.util.Optional.empty();
    }
    var matcher = RUNNER.matcher(test.getUniqueId());
    String found = null;
    while (matcher.find()) {
      found = matcher.group("runner");
    }
    return java.util.Optional.ofNullable(found);
  }

  private static final java.util.regex.Pattern RUNNER =
      java.util.regex.Pattern.compile("\\[runner:(?<runner>[\\w.$]+)\\]");

  /// `com.example.FooTest#bar(java.nio.file.Path)` → `bar`.
  private static String method(String test) {
    var method = test.substring(test.indexOf('#') + 1);
    int open = method.indexOf('(');
    return open < 0 ? method : method.substring(0, open);
  }

  /// `com.example.FooTest#bar(java.nio.file.Path)` → `java.nio.file.Path`; empty for none.
  private static String parameters(String test) {
    int open = test.indexOf('(');
    return open < 0 ? "" : test.substring(open + 1, test.length() - 1);
  }

  private static Set<String> union(Set<String> first, Set<String> second) {
    var all = new HashSet<>(first);
    all.addAll(second);
    return all;
  }

  private static List<Path> concat(List<Path> first, List<Path> second) {
    var all = new java.util.ArrayList<>(first);
    all.addAll(second);
    return all;
  }

  /// Top-level test classes whose package also holds production classes.
  private static Set<String> splitTests(List<Path> productionRoots, List<Path> testRoots)
      throws IOException {
    var packages =
        classNamesIn(productionRoots).stream().map(Worker::packageOf).collect(Collectors.toSet());
    return classNamesIn(testRoots).stream()
        .filter(name -> name.indexOf('$') < 0 && packages.contains(packageOf(name)))
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Set<String> classNamesIn(List<Path> roots) throws IOException {
    var names = new HashSet<String>();
    for (Path root : roots) {
      if (Files.isDirectory(root)) {
        try (Stream<Path> files = Files.walk(root)) {
          files
              .filter(file -> file.toString().endsWith(".class"))
              .map(
                  file ->
                      root.relativize(file)
                          .toString()
                          .replace(root.getFileSystem().getSeparator(), ".")
                          .replaceAll("\\.class$", ""))
              .forEach(names::add);
        }
      }
    }
    return names;
  }

  private static String packageOf(String name) {
    return name.lastIndexOf('.') < 0 ? "" : name.substring(0, name.lastIndexOf('.'));
  }

  /// The engine's commands, as they come, read on a thread of their own.
  private static BlockingQueue<String> readCommands() {
    var commands = new LinkedBlockingQueue<String>();
    var reader =
        new Thread(
            () -> {
              try (var in = new BufferedReader(new InputStreamReader(System.in, UTF_8))) {
                for (String line = in.readLine(); line != null; line = in.readLine()) {
                  commands.add(line);
                }
              } catch (IOException e) {
                // the engine went away
              }
              // No engine, no reason to live: halt now, even mid-test. Otherwise a worker
              // outlives a killed engine and keeps its ports for the next run to trip on.
              haltWithWhatTestsStarted();
            },
            "mutastic-protocol");
    reader.setDaemon(true);
    reader.start();
    return commands;
  }

  private static List<Path> roots(String[] args, String flag) {
    return Arrays.stream(args)
        .filter(arg -> arg.startsWith(flag))
        .flatMap(arg -> Arrays.stream(arg.substring(flag.length()).split(File.pathSeparator, -1)))
        .filter(entry -> !entry.isEmpty())
        .map(Path::of)
        .map(Worker::root)
        .toList();
  }

  /// A directory as it is; a jar as the root of what's in it, as another module of the build
  /// reaches a module's tests. A multi-release jar's classes are the ones this JDK would load from
  /// it, `META-INF/versions/11`'s over the base on 11 and later, as the JVM's own class path does;
  /// a directory is never read that way, on the class path or here.
  static Path root(Path entry) {
    if (!entry.toString().endsWith(".jar") || !Files.isRegularFile(entry)) {
      return entry;
    }
    try {
      return FileSystems.newFileSystem(entry, Map.of("releaseVersion", "runtime")).getPath("/");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
