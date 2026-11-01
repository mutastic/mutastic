package io.mutastic.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/// One mutation-testing run of one project: find every mutant, run the tests once with coverage,
/// then run each mutant against only the tests that reach it, fastest first, on several worker
/// JVMs side by side.
public final class Engine {
  /// How much slower than in the coverage pass a mutant's tests may run before they count as
  /// stuck, and the fixed allowance on top, for JIT warm-up and GC pauses.
  private static final double TIMEOUT_FACTOR = 1.25;
  private static final Duration TIMEOUT_ALLOWANCE = Duration.ofSeconds(4);

  private final Path workerJar;
  private final Path log;

  /// `workerJar` is the test worker, `mutastic-worker.jar`; `log` collects the tests' output
  /// (`worker.log`, and `worker-2.log` and so on for the other workers).
  public Engine(Path workerJar, Path log) {
    this.workerJar = workerJar;
    this.log = log;
  }

  /// Workers to run side by side when not told: half the cores, as tests often use more than
  /// one thread, and at most 4, since each worker holds its own copy of the app.
  public static int defaultWorkers() {
    return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
  }

  /// The tests failed before any mutant: nothing a mutant does can be judged against them.
  public static final class BaselineFailed extends RuntimeException {
    private final List<String> failing;

    BaselineFailed(List<String> failing, Path log) {
      super("the tests fail without any mutant: " + String.join(", ", failing) + "; their output is in " + log);
      this.failing = List.copyOf(failing);
    }

    public List<String> failing() {
      return failing;
    }
  }

  /// @param only run just the mutant with this id
  /// @param confirmCatches run a catching test once more against its mutant: a catch that
  ///     doesn't repeat came from a flaky test, and the mutant's other covering tests decide.
  ///     One more test run per catch.
  /// @param workers how many worker JVMs run mutants side by side
  public record Options(Optional<String> only, boolean confirmCatches, int workers) {
    public Options {
      if (workers < 1) {
        throw new IllegalArgumentException("workers must be at least 1, not " + workers);
      }
    }
  }

  /// Runs the mutants. Each result goes to `finished` as soon as it's known.
  public Report run(Project project, Options options, Listener listener) {
    var only = options.only();
    long start = System.nanoTime();
    var context = ClassFiles.resolvingFrom(Stream.of(project.classes(), project.testClasses(), project.dependencies())
        .flatMap(List::stream).toList());
    var mutations = new Mutations(context);
    var models = classesIn(project.classes(), context);
    var mutants = models.values().stream()
        .flatMap(model -> mutations.find(model).stream())
        .filter(mutant -> only.map(mutant.id()::equals).orElse(true))
        .toList();
    if (only.isPresent() && mutants.isEmpty()) {
      throw new IllegalArgumentException("no mutant " + only.get() + " in " + project.name());
    }

    var coverage = new Coverage(context);
    var mutated = mutants.stream().filter(mutant -> mutant.skipped().isEmpty()).map(Mutant::binaryName)
        .collect(Collectors.toSet());
    var instrumented = new LinkedHashMap<String, byte[]>();
    models.forEach((name, model) -> {
      if (mutated.contains(name)) {
        instrumented.put(name, coverage.instrument(model));
      }
    });

    var workers = new ArrayList<TestWorker>();
    for (int number = 1; number <= Math.min(options.workers(), Math.max(1, mutants.size())); number++) {
      workers.add(new TestWorker(project, workerJar, number == 1 ? log : numbered(log, number)));
    }
    try {
      var first = workers.getFirst();
      long coverageStart = System.nanoTime();
      var baseline = first.cover(instrumented, testClassesIn(project.testClasses()), coverage);
      var flaky = baseline.passed() ? List.<String>of() : flakyOrFail(baseline.failing(), coverage, first);
      coverage.exclude(flaky);
      var coverageTook = Duration.ofNanos(System.nanoTime() - coverageStart);
      if (coverage.testCount() == 0) {
        throw new IllegalStateException("no tests ran in " + project.name() + "; mutastic runs JUnit Platform tests");
      }
      listener.coverageFinished(first.jdk(), coverage.testCount(), coverageTook, mutants.size(), flaky);
      var mutantRun = new MutantRun(models, mutations, coverage, options.confirmCatches());
      var results = runAll(mutants, mutantRun, workers, listener);
      return new Report(project.name(), first.jdk(), results, coverage.testCount(), flaky, coverageTook, workers.size(),
          workers.stream().mapToInt(TestWorker::started).sum(), Duration.ofNanos(System.nanoTime() - start));
    } finally {
      workers.forEach(TestWorker::close);
    }
  }

  /// Every mutant, on the workers side by side, in the order they were found. A catch that a port
  /// in use caused may be another worker's test holding the port: those mutants run again
  /// afterwards on one worker, with the others stopped, and are reported then.
  private static List<MutantResult> runAll(List<Mutant> mutants, MutantRun mutantRun, List<TestWorker> workers,
      Listener listener) {
    var results = new MutantResult[mutants.size()];
    var clashed = new java.util.concurrent.ConcurrentLinkedQueue<Integer>();
    var idle = new java.util.concurrent.LinkedBlockingQueue<TestWorker>(workers);
    var report = new Object();
    try (var executor = java.util.concurrent.Executors.newFixedThreadPool(workers.size(),
        Thread.ofPlatform().name("mutastic-", 1).daemon().factory())) {
      var futures = new ArrayList<java.util.concurrent.Future<?>>();
      for (int index = 0; index < mutants.size(); index++) {
        int at = index;
        futures.add(executor.submit(() -> {
          var worker = take(idle);
          try {
            var result = mutantRun.run(mutants.get(at), worker);
            if (result.portClash() && workers.size() > 1) {
              clashed.add(at);
              return;
            }
            results[at] = result.result();
            synchronized (report) {
              listener.mutantFinished(result.result());
            }
          } finally {
            idle.add(worker);
          }
        }));
      }
      for (var future : futures) {
        await(future);
      }
    }
    if (!clashed.isEmpty()) {
      workers.stream().skip(1).forEach(TestWorker::close); // nothing else holds a port now
      for (int at : clashed.stream().sorted().toList()) {
        results[at] = mutantRun.run(mutants.get(at), workers.getFirst()).result();
        listener.mutantFinished(results[at]);
      }
    }
    return List.of(results);
  }

  private static TestWorker take(java.util.concurrent.BlockingQueue<TestWorker> idle) {
    try {
      return idle.take();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    }
  }

  /// Waits for one mutant, passing on what went wrong in it as if it had happened here.
  private static void await(java.util.concurrent.Future<?> future) {
    try {
      future.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException("interrupted", e);
    } catch (java.util.concurrent.ExecutionException e) {
      if (e.getCause() instanceof RuntimeException runtime) {
        throw runtime;
      }
      throw new IllegalStateException(e.getCause());
    }
  }

  /// `worker-2.log` beside `worker.log`.
  private static Path numbered(Path log, int number) {
    var name = log.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return log.resolveSibling(dot < 0 ? name + "-" + number : name.substring(0, dot) + "-" + number + name.substring(dot));
  }

  /// The tests that failed in the coverage pass, if every one passes when run again on its own:
  /// they're flaky, and the run goes on without them. A test that fails twice means the tests
  /// are red, and nothing a mutant does can be judged against them.
  ///
  /// A flaky test left in would fail at random against mutants too, and its failures would
  /// count as catches it never made.
  private List<String> flakyOrFail(List<String> failing, Coverage coverage, TestWorker worker) {
    // A test class that failed as a whole (a `@BeforeAll`) is reported by its unique id, which
    // can't be run again on its own.
    boolean rerunnable = failing.stream().allMatch(test -> test.contains("#"));
    var stillFailing = rerunnable
        ? failing.stream().filter(test -> !worker.passes(test, timeoutFor(coverage.timeOf(test)))).toList()
        : failing;
    if (!stillFailing.isEmpty()) {
      throw new BaselineFailed(stillFailing, log);
    }
    return failing;
  }

  private static Duration timeoutFor(Duration normal) {
    return Duration.ofNanos((long) (normal.toNanos() * TIMEOUT_FACTOR)).plus(TIMEOUT_ALLOWANCE);
  }

  /// What one mutant needs from the run, shared by every worker's thread. Applying and
  /// verifying a mutant share the ClassFile context's caches, so they take turns; only the
  /// tests run side by side. Coverage is only read once the coverage pass is over.
  private record MutantRun(Map<String, ClassModel> models, Mutations mutations, Coverage coverage, boolean confirmCatches) {
    record Outcome(MutantResult result, boolean portClash) {}

    Outcome run(Mutant mutant, TestWorker worker) {
      if (mutant.skipped().isPresent()) {
        return new Outcome(MutantResult.skipped(mutant), false);
      }
      var covering = coverage.coveringTests(mutant);
      if (covering.isEmpty()) {
        return new Outcome(MutantResult.notCovered(mutant), false);
      }
      byte[] bytes;
      synchronized (mutations) {
        bytes = mutations.apply(models.get(mutant.binaryName()), mutant);
        var errors = mutations.verify(bytes);
        if (!errors.isEmpty()) {
          // A mutant that won't load proves nothing, and counting it as caught would flatter the
          // score: this is mutastic's bug, not the user's.
          throw new IllegalStateException("mutant " + mutant.id() + " doesn't verify: " + errors.getFirst().getMessage());
        }
      }
      long start = System.nanoTime();
      var run = worker.run(mutant.binaryName(), bytes, covering, timeoutFor(coverage.timeOf(covering)));
      Optional<String> unconfirmed = Optional.empty();
      if (confirmCatches && run.status() == Status.CAUGHT) {
        var catcher = run.failedTest().orElseThrow();
        var again = worker.run(mutant.binaryName(), bytes, List.of(catcher), timeoutFor(coverage.timeOf(catcher)));
        if (again.status() == Status.MISSED) {
          // Not a catch: the tests after it decide, as if it had passed the first time.
          unconfirmed = Optional.of(catcher);
          var rest = covering.subList(covering.indexOf(catcher) + 1, covering.size());
          int ran = run.testsRun();
          var after = rest.isEmpty()
              ? new TestWorker.Run(Status.MISSED, 0)
              : worker.run(mutant.binaryName(), bytes, rest, timeoutFor(coverage.timeOf(rest)));
          run = new TestWorker.Run(after.status(), after.failedTest(), after.failure(), after.origin(),
              ran + after.testsRun(), after.portClash());
        }
      }
      var result = new MutantResult(mutant, run.status(), covering, run.failedTest(), run.failure(), run.origin(),
          run.testsRun(), unconfirmed, Duration.ofNanos(System.nanoTime() - start));
      return new Outcome(result, run.portClash());
    }
  }

  /// Every class in the directories, by binary name, in a stable order.
  private static Map<String, ClassModel> classesIn(List<Path> roots, ClassFile context) {
    var models = new java.util.TreeMap<String, ClassModel>();
    for (Path file : classFiles(roots)) {
      try {
        var model = context.parse(Files.readAllBytes(file));
        models.put(model.thisClass().asInternalName().replace('/', '.'), model);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return models;
  }

  /// Top-level classes in the test directories: JUnit finds the tests in them, nested ones
  /// included, and ignores classes with none.
  private static List<String> testClassesIn(List<Path> roots) {
    var names = new ArrayList<String>();
    for (Path root : roots) {
      for (Path file : classFiles(List.of(root))) {
        var name = root.relativize(file).toString().replace(java.io.File.separatorChar, '.').replaceAll("\\.class$", "");
        if (name.indexOf('$') < 0 && !name.endsWith("module-info") && !name.endsWith("package-info")) {
          names.add(name);
        }
      }
    }
    return names.stream().sorted().toList();
  }

  private static List<Path> classFiles(List<Path> roots) {
    var files = new ArrayList<Path>();
    for (Path root : roots) {
      if (!Files.isDirectory(root)) {
        continue;
      }
      try (Stream<Path> walk = Files.walk(root)) {
        walk.filter(file -> file.toString().endsWith(".class")).sorted().forEach(files::add);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return files;
  }
}
