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
/// then run each mutant against only the tests that reach it, fastest first.
public final class Engine {
  /// How much slower than in the coverage pass a mutant's tests may run before they count as
  /// stuck, and the fixed allowance on top, for JIT warm-up and GC pauses.
  private static final double TIMEOUT_FACTOR = 1.25;
  private static final Duration TIMEOUT_ALLOWANCE = Duration.ofSeconds(4);

  private final Path workerJar;
  private final Path log;

  /// `workerJar` is the test worker, `mutastic-worker.jar`; `log` collects the tests' output.
  public Engine(Path workerJar, Path log) {
    this.workerJar = workerJar;
    this.log = log;
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
  public record Options(Optional<String> only, boolean confirmCatches) {
    public static final Options DEFAULT = new Options(Optional.empty(), false);
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

    try (var worker = new TestWorker(project, workerJar, log)) {
      long coverageStart = System.nanoTime();
      var baseline = worker.cover(instrumented, testClassesIn(project.testClasses()), coverage);
      var flaky = baseline.passed() ? List.<String>of() : flakyOrFail(baseline.failing(), coverage, worker);
      coverage.exclude(flaky);
      var coverageTook = Duration.ofNanos(System.nanoTime() - coverageStart);
      if (coverage.testCount() == 0) {
        throw new IllegalStateException("no tests ran in " + project.name() + "; mutastic runs JUnit Platform tests");
      }
      listener.coverageFinished(worker.jdk(), coverage.testCount(), coverageTook, mutants.size(), flaky);
      var results = new ArrayList<MutantResult>();
      for (Mutant mutant : mutants) {
        var result = run(mutant, models.get(mutant.binaryName()), mutations, coverage, worker, options.confirmCatches());
        results.add(result);
        listener.mutantFinished(result);
      }
      return new Report(project.name(), worker.jdk(), results, coverage.testCount(), flaky, coverageTook, worker.started(),
          Duration.ofNanos(System.nanoTime() - start));
    }
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

  private static MutantResult run(Mutant mutant, ClassModel model, Mutations mutations, Coverage coverage, TestWorker worker,
      boolean confirmCatches) {
    if (mutant.skipped().isPresent()) {
      return MutantResult.skipped(mutant);
    }
    var covering = coverage.coveringTests(mutant);
    if (covering.isEmpty()) {
      return MutantResult.notCovered(mutant);
    }
    var bytes = mutations.apply(model, mutant);
    var errors = mutations.verify(bytes);
    if (!errors.isEmpty()) {
      // A mutant that won't load proves nothing, and counting it as caught would flatter the
      // score: this is mutastic's bug, not the user's.
      throw new IllegalStateException("mutant " + mutant.id() + " doesn't verify: " + errors.getFirst().getMessage());
    }
    var timeout = timeoutFor(coverage.timeOf(covering));
    long start = System.nanoTime();
    var run = worker.run(mutant.binaryName(), bytes, covering, timeout);
    Optional<String> unconfirmed = Optional.empty();
    if (confirmCatches && run.status() == Status.CAUGHT) {
      var catcher = run.failedTest().orElseThrow();
      var again = worker.run(mutant.binaryName(), bytes, List.of(catcher), timeoutFor(coverage.timeOf(catcher)));
      if (again.status() == Status.MISSED) {
        // Not a catch: the tests after it decide, as if it had passed the first time.
        unconfirmed = Optional.of(catcher);
        var rest = covering.subList(covering.indexOf(catcher) + 1, covering.size());
        var ran = run.testsRun();
        run = rest.isEmpty()
            ? new TestWorker.Run(Status.MISSED, Optional.empty(), Optional.empty(), Optional.empty(), ran)
            : worker.run(mutant.binaryName(), bytes, rest, timeoutFor(coverage.timeOf(rest)));
        run = new TestWorker.Run(run.status(), run.failedTest(), run.failure(), run.origin(), ran + (rest.isEmpty() ? 0 : run.testsRun()));
      }
    }
    return new MutantResult(mutant, run.status(), covering, run.failedTest(), run.failure(), run.origin(), run.testsRun(),
        unconfirmed, Duration.ofNanos(System.nanoTime() - start));
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
