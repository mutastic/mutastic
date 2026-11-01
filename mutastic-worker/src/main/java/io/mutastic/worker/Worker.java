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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.platform.engine.TestExecutionResult;
import org.junit.platform.engine.support.descriptor.MethodSource;
import org.junit.platform.launcher.TestExecutionListener;
import org.junit.platform.launcher.TestIdentifier;
import org.junit.platform.launcher.core.LauncherDiscoveryRequestBuilder;
import org.junit.platform.launcher.core.LauncherFactory;

/// Runs the project's tests on the project's JDK, for an engine on a newer one. The engine sends
/// finished classes (instrumented or mutated) and the tests to run; the worker loads them, runs
/// the JUnit Platform, and answers on stdout. Test output goes to stderr, so a test that prints
/// can't corrupt the protocol.
///
/// Arguments: `--production=<dirs>` (main classes and resources) and `--tests=<dirs>` (test
/// classes and resources), each separated by the platform's path separator.
///
/// Protocol, one command per line, fields separated by tabs (test ids can contain spaces:
/// `#play(java.lang.String, int)`):
///
/// | Engine sends | Worker answers |
/// |---|---|
/// | `CLASS <name> <base64>`: use these bytes for the next command | nothing |
/// | `COVER <test>...`: run every test, recording lines and arguments | `TEST <id> <nanos>`, `LINE <id> <ids>` and `VALUE <id> <parameter> <values>` lines, then `DONE PASSED` or `DONE FAILED <id>...` |
/// | `RUN <test>...`: run in order until one fails | `RESULT PASSED <ran>`, `RESULT FAILED <ran> <test> <exception> <origin> <port-clash or ->` or `RESULT ERROR <why>` |
/// | `SUGGEST <class> <method> <descriptor> <base64> <inputs>`: find inputs where a mutant changes the result; inputs are `1;2\|3;4`, `-` for none | `SUGGESTED <inputs> <today> <with mutant> <stuck or ->`, or `SUGGESTED -` |
/// | `EXIT` | nothing; the worker exits |
///
/// A test is `com.example.PricingTest#method` or a class name. The engine kills the worker if it
/// doesn't answer in time: a mutant that loops forever can't be stopped any other way.
public final class Worker {
  private final List<Path> productionRoots;
  private final List<Path> testRoots;
  private final Set<String> splitTests;
  private final Set<String> testClassNames;
  private final PrintStream protocol;
  private final Map<String, byte[]> staged = new HashMap<>();
  private ProductionLoader production;
  private int runs;
  private String lastFailure = "unknown";
  private String lastOrigin = "unknown";
  private boolean lastPortClash;

  private Worker(List<Path> productionRoots, List<Path> testRoots, PrintStream protocol) throws IOException {
    this.productionRoots = productionRoots;
    this.testRoots = testRoots;
    this.protocol = protocol;
    this.splitTests = splitTests(productionRoots, testRoots);
    this.testClassNames = classNamesIn(testRoots);
  }

  public static void main(String[] args) throws Exception {
    var protocol = new PrintStream(new FileOutputStream(FileDescriptor.out), true, UTF_8);
    System.setOut(System.err);
    var worker = new Worker(roots(args, "--production="), roots(args, "--tests="), protocol);
    var commands = new LinkedBlockingQueue<String>();
    var reader = new Thread(() -> {
      try (var in = new BufferedReader(new InputStreamReader(System.in, UTF_8))) {
        for (String line = in.readLine(); line != null; line = in.readLine()) {
          commands.add(line);
        }
      } catch (IOException e) {
        // the engine went away
      }
      // No engine, no reason to live: halt now, even mid-test. Otherwise a worker outlives a
      // killed engine and keeps its ports for the next run to trip on.
      Runtime.getRuntime().halt(0);
    }, "mutastic-protocol");
    reader.setDaemon(true);
    reader.start();
    protocol.println("READY\t" + Runtime.version().feature());
    for (String line = commands.take(); !line.equals("EXIT"); line = commands.take()) {
      worker.handle(line.split("\t"));
    }
    Runtime.getRuntime().halt(0); // even if a test left non-daemon threads behind
  }

  private void handle(String[] command) throws Exception {
    var tests = Arrays.asList(command).subList(1, command.length);
    switch (command[0]) {
      case "CLASS" -> staged.put(command[1], Base64.getDecoder().decode(command[2]));
      case "COVER" -> {
        production = new ProductionLoader(productionRoots, staged, testRoots, splitTests, getClass().getClassLoader());
        cover(tests);
        production.restoreAll();
        staged.clear();
      }
      case "RUN" -> {
        if (production == null) { // a new worker, after the last one was killed
          production = new ProductionLoader(productionRoots, Map.of(), testRoots, splitTests, getClass().getClassLoader());
        }
        production.swapIn(staged);
        try {
          run(tests);
        } finally {
          production.swapBack(staged.keySet());
          staged.clear();
        }
      }
      case "SUGGEST" -> {
        if (production == null) { // a new worker, after the last one was killed
          production = new ProductionLoader(productionRoots, Map.of(), testRoots, splitTests, getClass().getClassLoader());
        }
        var candidates = Arrays.stream(command[5].split("\\|"))
            .map(inputs -> inputs.equals("-") ? List.<Long>of() : Arrays.stream(inputs.split(";")).map(Long::valueOf).toList())
            .toList();
        var found = Suggester.find(production, command[1], command[2], command[3], Base64.getDecoder().decode(command[4]),
            candidates);
        protocol.println(found.inputs()
            .map(inputs -> "SUGGESTED\t" + inputs.stream().map(String::valueOf).collect(Collectors.joining(";"))
                + "\t" + found.today() + "\t" + found.mutated() + "\t" + (found.stuck() ? "stuck" : "-"))
            .orElse("SUGGESTED\t-"));
      }
      default -> protocol.println("RESULT\tERROR\tunknown command " + command[0]);
    }
  }

  private void cover(List<String> tests) {
    Probes.reset();
    var startedAt = new HashMap<String, Long>();
    var failing = execute(tests, new TestExecutionListener() {
      @Override
      public void executionStarted(TestIdentifier test) {
        if (test.isTest()) {
          Probes.startTest(id(test));
          startedAt.put(id(test), System.nanoTime());
        }
      }

      @Override
      public void executionFinished(TestIdentifier test, TestExecutionResult result) {
        if (test.isTest()) {
          protocol.println("TEST\t" + id(test) + "\t" + (System.nanoTime() - startedAt.get(id(test))));
        }
      }
    });
    Probes.flush();
    Probes.linesByTest().forEach((test, lines) -> {
      if (!lines.isEmpty()) {
        protocol.println("LINE\t" + test + "\t" + lines.stream().map(String::valueOf).collect(Collectors.joining(",")));
      }
    });
    Probes.valuesByTest().forEach((test, byParameter) -> byParameter.forEach((parameter, values) ->
        protocol.println("VALUE\t" + test + "\t" + parameter + "\t"
            + values.stream().sorted().map(String::valueOf).collect(Collectors.joining(",")))));
    protocol.println("DONE\t" + (failing.isEmpty() ? "PASSED" : "FAILED\t" + String.join("\t", failing)));
  }

  /// The engine orders tests fastest first; the worker stops at the first failure.
  private void run(List<String> tests) {
    int ran = 0;
    for (String test : tests) {
      ran++;
      var started = new int[1];
      var failing = execute(List.of(test), new TestExecutionListener() {
        @Override
        public void executionStarted(TestIdentifier identifier) {
          if (identifier.isTest()) {
            started[0]++;
          }
        }
      });
      // A selector that matches nothing runs nothing and fails nothing: that's not a pass.
      if (started[0] == 0 && failing.isEmpty()) {
        protocol.println("RESULT\tERROR\tno test ran for " + test);
        return;
      }
      if (!failing.isEmpty()) {
        protocol.println("RESULT\tFAILED\t" + ran + "\t" + test + "\t" + lastFailure + "\t" + lastOrigin
            + "\t" + (lastPortClash ? "port-clash" : "-"));
        return;
      }
    }
    protocol.println("RESULT\tPASSED\t" + ran);
  }

  private List<String> execute(List<String> tests, TestExecutionListener observer) {
    var loader = new TestLoader(testRoots, production, ++runs);
    var thread = Thread.currentThread();
    var previous = thread.getContextClassLoader();
    thread.setContextClassLoader(loader);
    try {
      var request = LauncherDiscoveryRequestBuilder.request();
      for (String test : tests) {
        int hash = test.indexOf('#');
        request.selectors(hash < 0
            ? selectClass(loader.loadClass(test))
            : selectMethod(loader.loadClass(test.substring(0, hash)), method(test), parameters(test)));
      }
      var failing = new ArrayList<String>();
      LauncherFactory.create().execute(request.build(), new TestExecutionListener() {
        @Override
        public void executionFinished(TestIdentifier test, TestExecutionResult result) {
          // Containers too: a mutant that breaks a test class's set-up (a static initialiser,
          // `@BeforeAll`) fails the class, and its tests never start.
          if (result.getStatus() != TestExecutionResult.Status.SUCCESSFUL) {
            failing.add(id(test));
            lastFailure = result.getThrowable().map(thrown -> thrown.getClass().getName()).orElse("unknown");
            lastOrigin = result.getThrowable().map(Worker.this::origin).orElse("unknown");
            lastPortClash = result.getThrowable().map(Worker::portClash).orElse(false);
            System.err.println("FAILED\t" + id(test));
            result.getThrowable().ifPresent(thrown -> thrown.printStackTrace(System.err));
          }
        }
      }, observer);
      return failing;
    } catch (ClassNotFoundException e) {
      throw new IllegalStateException(e);
    } finally {
      thread.setContextClassLoader(previous);
    }
  }

  /// Whether a port in use caused it, anywhere in its causes: with workers running side by side,
  /// that can be another worker's test holding the port, not the mutant.
  private static boolean portClash(Throwable thrown) {
    for (Throwable cause = thrown; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
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
      if (testClassNames.contains(dollar < 0 ? name : name.substring(0, dollar)) || testClassNames.contains(name)) {
        return "test";
      }
      if (production.classFile(name) != null) {
        return "code";
      }
    }
    return "unknown";
  }

  /// `com.example.PricingTest#discount(java.nio.file.Path)`, under the name the build gave the
  /// class. Parameter types too: `selectMethod(class, "name")` finds only a no-argument method,
  /// so a test taking a `@TempDir Path` would select nothing.
  private static String id(TestIdentifier test) {
    return Renamer.original(test.getSource()
        .filter(MethodSource.class::isInstance)
        .map(MethodSource.class::cast)
        .map(source -> source.getClassName() + "#" + source.getMethodName()
            + (source.getMethodParameterTypes().isEmpty() ? "" : "(" + source.getMethodParameterTypes() + ")"))
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

  /// Top-level test classes whose package also holds production classes.
  private static Set<String> splitTests(List<Path> productionRoots, List<Path> testRoots) throws IOException {
    var packages = classNamesIn(productionRoots).stream().map(Worker::packageOf).collect(Collectors.toSet());
    return classNamesIn(testRoots).stream()
        .filter(name -> name.indexOf('$') < 0 && packages.contains(packageOf(name)))
        .collect(Collectors.toUnmodifiableSet());
  }

  private static Set<String> classNamesIn(List<Path> roots) throws IOException {
    var names = new HashSet<String>();
    for (Path root : roots) {
      if (Files.isDirectory(root)) {
        try (Stream<Path> files = Files.walk(root)) {
          files.filter(file -> file.toString().endsWith(".class"))
              .map(file -> root.relativize(file).toString().replace(File.separatorChar, '.').replaceAll("\\.class$", ""))
              .forEach(names::add);
        }
      }
    }
    return names;
  }

  private static String packageOf(String name) {
    return name.lastIndexOf('.') < 0 ? "" : name.substring(0, name.lastIndexOf('.'));
  }

  private static List<Path> roots(String[] args, String flag) {
    return Arrays.stream(args)
        .filter(arg -> arg.startsWith(flag))
        .flatMap(arg -> Arrays.stream(arg.substring(flag.length()).split(File.pathSeparator)))
        .filter(entry -> !entry.isEmpty())
        .map(Path::of)
        .toList();
  }
}
