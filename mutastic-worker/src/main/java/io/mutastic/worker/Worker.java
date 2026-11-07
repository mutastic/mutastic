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
import org.junit.platform.engine.TestExecutionResult;
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
/// and resources), each separated by the platform's path separator.
///
/// Protocol, one command per line, fields separated by tabs (test ids can contain spaces:
/// `#play(java.lang.String, int)`):
///
/// | Engine sends | Worker answers |
/// |---|---|
/// | `CLASS <name> <base64>`: use these bytes for the next command | nothing |
/// | `COVER <test>...`: run every test, recording lines and arguments | `TEST <id> <nanos>`, `LINE <id> <ids>` and `VALUE <id> <parameter> <values>` lines, then `DONE PASSED` or `DONE FAILED <id>...` |
/// | `COVER` answers also hold `LOADING <ids>`: the lines some test ran, but only while a class was loading, and `LOADED <class>...`: the production classes it loaded, in order | |
/// | `WARM <class>...`: load and initialise them, with no mutant in, as a worker that didn't run `COVER` must before its first mutant | `WARMED`, or `NOT-WARMED <class>` for the first that couldn't be |
/// | `RUN <test>...`: run in order until one fails | `RESULT PASSED <ran>`, `RESULT FAILED <ran> <test> <exception> <origin> <port-clash or ->` or `RESULT ERROR <why>`, after `AGED` if this worker is now worth replacing ([Aging]) |
/// | `FRESH <test>...`: the same, with every class loaded anew, so static initialisers run with the staged classes | as for `RUN` |
/// | `SUGGEST <class> <method> <descriptor> <base64> <inputs> <receiver>`: find inputs where a mutant changes the result; inputs are `1;2\|3;4`, `-` for none; the receiver is `-` for a static method, else a constructor descriptor whose arguments start each input, or for an inner class its outer class's receiver, `>`, then its own | `SUGGESTED <inputs> <today> <with mutant> <stuck or ->`, or `SUGGESTED -` |
/// | `EXIT` | nothing; the worker exits |
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
  private final Map<String, byte[]> staged = new HashMap<>();
  private final MutantInPlace inPlace = new MutantInPlace();
  private final Aging aging = new Aging(WORTH_A_NEW_WORKER);

  /// Made by the coverage pass, or by the first command a new worker gets after one was killed.
  private @Nullable ProductionLoader production;

  /// For tests run in a framework that loads the app itself (`--framework`): see [Framework].
  private final @Nullable Framework framework;

  private int runs;
  private String lastFailure = "unknown";
  private String lastOrigin = "unknown";
  private boolean lastPortClash;

  /// Each failing test's exception and its causes, `java.io.UncheckedIOException>java.net
  /// .ConnectException`: for the coverage pass's `FAILURE` lines, which say why a test that then
  /// passed was flaky.
  private final Map<String, String> failures = new java.util.LinkedHashMap<>();

  /// The JUnit tags the build's test task runs by: every run here is filtered the same way.
  record Tags(List<String> include, List<String> exclude) {
    static Tags of(String[] args) {
      return new Tags(values(args, "--include-tags="), values(args, "--exclude-tags="));
    }

    void filter(LauncherDiscoveryRequestBuilder request) {
      if (!include.isEmpty()) {
        request.filters(TagFilter.includeTags(include));
      }
      if (!exclude.isEmpty()) {
        request.filters(TagFilter.excludeTags(exclude));
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
    var providers = TestSplit.providersIn(concat(testRoots, productionRoots));
    var renamed = new HashSet<>(splitTests(productionRoots, testRoots));
    renamed.removeAll(providers);
    this.splitTests = new TestSplit(renamed, providers);
    this.testClassNames = classNamesIn(testRoots);
    this.framework =
        framework ? new Framework(productionRoots, classNamesIn(productionRoots)) : null;
  }

  public static void main(String[] args) throws Exception {
    var protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF_8);
    System.setOut(System.err);
    var worker =
        new Worker(
            roots(args, "--production="),
            roots(args, "--tests="),
            Tags.of(args),
            protocol,
            Arrays.asList(args).contains("--framework"));
    var commands = readCommands();
    protocol.println("READY\t" + Runtime.version().feature());
    for (String line = commands.take(); !line.equals("EXIT"); line = commands.take()) {
      worker.handle(line.split("\t", -1));
    }
    writeCoverage();
    haltWithWhatTestsStarted(); // even if a test left non-daemon threads behind
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
    if (!command[0].equals("CLASS") && !command[0].equals("RUN") && production != null) {
      inPlace.restore(production);
    }
    switch (command[0]) {
      case "CLASS" -> staged.put(command[1], Base64.getDecoder().decode(command[2]));
      case "COVER" -> coverInstrumented(tests);
      case "WARM" -> warm(tests);
      case "RUN" -> runWithStaged(tests);
      case "FRESH" -> runFresh(tests);
      case "SUGGEST" -> suggest(command);
      default -> protocol.println("RESULT\tERROR\tunknown command " + command[0]);
    }
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
            productionRoots, staged, testRoots, splitTests, getClass().getClassLoader());
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
            productionRoots, staged, testRoots, splitTests, getClass().getClassLoader());
    staged.clear();
    run(tests, fresh);
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
              productionRoots, Map.of(), testRoots, splitTests, getClass().getClassLoader());
      SystemLoader.project(production);
    }
    return production;
  }

  private void cover(List<String> tests) {
    Probes.reset();
    failures.clear();
    var scopes = new TestScopes(Worker::id, framework == null);
    var failing = execute(tests, production(), timingTests(), scopes);
    Probes.flush();
    scopes.credit();
    reportLines();
    reportOnlyAtLoad();
    reportValues();
    reportLoaded();
    failures.forEach((test, chain) -> protocol.println("FAILURE\t" + test + "\t" + chain));
    protocol.println(
        "DONE\t" + (failing.isEmpty() ? "PASSED" : "FAILED\t" + String.join("\t", failing)));
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
    int ran = 0;
    for (String test : tests) {
      ran++;
      var started = new StartedTests();
      List<String> failing;
      try {
        failing = execute(List.of(test), loader, started);
      } catch (RuntimeException e) {
        // Finding the tests failed: TestNG makes the test classes as it finds them, so a mutant
        // that breaks a test class's constructor breaks it here, before any test can fail.
        remember(e);
        protocol.println(failedResult(ran, test));
        return;
      }
      // A selector that matches nothing runs nothing and fails nothing: that's not a pass.
      if (started.count == 0 && failing.isEmpty()) {
        protocol.println("RESULT\tERROR\tno test ran for " + test);
        return;
      }
      if (!failing.isEmpty()) {
        protocol.println(failedResult(ran, test));
        return;
      }
    }
    protocol.println("RESULT\tPASSED\t" + ran);
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
        + (lastPortClash ? "port-clash" : "-");
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
    thread.setContextClassLoader(loader);
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
              : selectMethod(
                  loader.loadClass(loader.copyOf(test.substring(0, hash))),
                  method(test),
                  parameters(test)));
    }
    return request.build();
  }

  /// Adds each failure's id to `failing` and keeps the last one's exception, origin and port clash
  /// for the `RESULT` line.
  private TestExecutionListener recordingFailures(List<String> failing) {
    return new TestExecutionListener() {
      @Override
      public void executionFinished(TestIdentifier test, TestExecutionResult result) {
        // Containers too: a mutant that breaks a test class's set-up (a static initialiser,
        // `@BeforeAll`) fails the class, and its tests never start.
        if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
          failing.add(id(test));
          lastFailure = "unknown";
          lastOrigin = "unknown";
          lastPortClash = false;
          result.getThrowable().ifPresent(Worker.this::remember);
          result.getThrowable().ifPresent(thrown -> failures.put(id(test), causes(thrown)));
          System.err.println("FAILED\t" + id(test));
          result.getThrowable().ifPresent(thrown -> thrown.printStackTrace(System.err));
        }
      }
    };
  }

  /// What the `RESULT` line says of a failure: its exception, origin and port clash.
  private void remember(Throwable thrown) {
    lastFailure = thrown.getClass().getName();
    lastOrigin = origin(thrown);
    lastPortClash = portClash(thrown);
  }

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
  /// a test taking a `@TempDir Path` would select nothing.
  static String id(TestIdentifier test) {
    return Renamer.original(
        test.getSource()
            .filter(MethodSource.class::isInstance)
            .map(MethodSource.class::cast)
            .map(
                source ->
                    source.getClassName()
                        + "#"
                        + source.getMethodName()
                        + (source.getMethodParameterTypes().isEmpty()
                            ? ""
                            : "(" + source.getMethodParameterTypes() + ")"))
            .orElse(test.getUniqueId()));
  }

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
  /// reaches a module's tests.
  static Path root(Path entry) {
    if (!entry.toString().endsWith(".jar") || !Files.isRegularFile(entry)) {
      return entry;
    }
    try {
      return FileSystems.newFileSystem(entry).getPath("/");
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
