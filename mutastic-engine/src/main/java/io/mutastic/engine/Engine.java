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
import java.util.function.Consumer;
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

  /// Runs every mutant, or only the one with id `only`. Each result goes to `finished` as soon
  /// as it's known.
  public Report run(Project project, Optional<String> only, Consumer<MutantResult> finished) {
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
    var mutated = mutants.stream().map(Mutant::binaryName).collect(Collectors.toSet());
    var instrumented = new LinkedHashMap<String, byte[]>();
    models.forEach((name, model) -> {
      if (mutated.contains(name)) {
        instrumented.put(name, coverage.instrument(model));
      }
    });

    try (var worker = new TestWorker(project, workerJar, log)) {
      var baseline = worker.cover(instrumented, testClassesIn(project.testClasses()), coverage);
      if (!baseline.passed()) {
        throw new BaselineFailed(baseline.failing(), log);
      }
      if (coverage.testCount() == 0) {
        throw new IllegalStateException("no tests ran in " + project.name() + "; mutastic runs JUnit Platform tests");
      }
      var results = new ArrayList<MutantResult>();
      for (Mutant mutant : mutants) {
        var result = run(mutant, models.get(mutant.binaryName()), mutations, coverage, worker);
        results.add(result);
        finished.accept(result);
      }
      return new Report(project.name(), worker.jdk(), results, coverage.testCount(), Duration.ofNanos(System.nanoTime() - start));
    }
  }

  private static MutantResult run(Mutant mutant, ClassModel model, Mutations mutations, Coverage coverage, TestWorker worker) {
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
    var normal = coverage.timeOf(covering);
    var timeout = Duration.ofNanos((long) (normal.toNanos() * TIMEOUT_FACTOR)).plus(TIMEOUT_ALLOWANCE);
    long start = System.nanoTime();
    var run = worker.run(mutant.binaryName(), bytes, covering, timeout);
    return new MutantResult(mutant, run.status(), covering, run.failedTest(), run.failure(), run.testsRun(),
        Duration.ofNanos(System.nanoTime() - start));
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
