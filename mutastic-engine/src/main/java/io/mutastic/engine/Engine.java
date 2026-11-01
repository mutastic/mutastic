package io.mutastic.engine;

import static java.util.Objects.requireNonNull;

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
/// then run each mutant against only the tests that reach it, fastest first, on several worker JVMs
/// side by side.
public final class Engine {
  /// How much slower than in the coverage pass a mutant's tests may run before they count as stuck,
  /// and the fixed allowance on top, for JIT warm-up and GC pauses.
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

  /// Workers to run side by side when not told: half the cores, as tests often use more than one
  /// thread, and at most 4, since each worker holds its own copy of the app.
  public static int defaultWorkers() {
    return Math.clamp(Runtime.getRuntime().availableProcessors() / 2, 1, 4);
  }

  /// `--only` named a mutant the project doesn't have.
  public static final class UnknownMutant extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    UnknownMutant(String id, String projectName) {
      super(
          ("no mutant %s in %s. A mutant's id belongs to the project its run was on: "
                  + "run the command as that run printed it, with the project's directory and module")
              .formatted(id, projectName));
    }
  }

  /// The tests failed before any mutant: nothing a mutant does can be judged against them.
  public static final class BaselineFailed extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    /// Not serialised: nothing sends this exception anywhere, it's reported where it's caught.
    private final transient List<String> failing;

    BaselineFailed(List<String> failing, Path log) {
      super(
          "the tests fail without any mutant: "
              + String.join(", ", failing)
              + "; their output is in "
              + log);
      this.failing = List.copyOf(failing);
    }

    public List<String> failing() {
      return failing;
    }
  }

  /// What a run does beyond finding and running every mutant.
  ///
  /// @param only run just the mutant with this id
  /// @param confirmCatches run a catching test once more against its mutant: a catch that doesn't
  ///   repeat came from a flaky test, and the mutant's other covering tests decide. One more test
  ///   run per catch.
  /// @param workers how many worker JVMs run mutants side by side
  /// @param suggestions look for a test that catches each missed mutant, by calling its method with
  ///   candidate inputs, today and with the mutant: user code runs outside its tests
  public record Options(
      Optional<String> only, boolean confirmCatches, int workers, boolean suggestions) {
    public Options {
      if (workers < 1) {
        throw new IllegalArgumentException("workers must be at least 1, not " + workers);
      }
    }
  }

  /// Runs the mutants. Each result goes to `finished` as soon as it's known.
  public Report run(Project project, Options options, Listener listener) {
    long start = System.nanoTime();
    var plan = plan(project, options);
    var mutants = plan.mutants();
    var workers = workersFor(project, options.workers(), mutants.size());
    try {
      var first = workers.getFirst();
      var pass = coveragePass(project, plan, first);
      listener.coverageFinished(pass);
      var results = new SideBySide(mutants, plan.mutantRun(), workers, listener).runAll();
      return new Report(
          project.name(),
          first.jdk(),
          results,
          plan.mutantRun().coverage().testCount(),
          pass.flaky(),
          pass.took(),
          workers.size(),
          workers.stream().mapToInt(TestWorker::started).sum(),
          Duration.ofNanos(System.nanoTime() - start));
    } finally {
      workers.forEach(TestWorker::close);
    }
  }

  /// A run's mutants and what running them needs, before any test has run.
  ///
  /// @param instrumented each class with a mutant to run, instrumented for the coverage pass
  private record Plan(
      List<Mutant> mutants, MutantRun mutantRun, Map<String, byte[]> instrumented) {}

  private static Plan plan(Project project, Options options) {
    var context = contextFor(project);
    var mutations = new Mutations(context);
    var models = classesIn(project.classes(), context);
    var mutants = mutantsIn(models, mutations, options.only(), project.name());
    var coverage = new Coverage(context);
    var instrumented = instrumentMutated(models, mutants, coverage);
    var mutantRun =
        new MutantRun(models, mutations, coverage, options.confirmCatches(), options.suggestions());
    return new Plan(mutants, mutantRun, instrumented);
  }

  /// Classes resolve from the project's own, its tests' and its dependencies'.
  private static ClassFile contextFor(Project project) {
    return ClassFiles.resolvingFrom(
        Stream.of(project.classes(), project.testClasses(), project.dependencies())
            .flatMap(List::stream)
            .toList());
  }

  /// Every mutant in the classes, or only the one with the id `only`, which must be there.
  private static List<Mutant> mutantsIn(
      Map<String, ClassModel> models,
      Mutations mutations,
      Optional<String> only,
      String projectName) {
    var mutants =
        models.values().stream()
            .flatMap(model -> mutations.find(model).stream())
            .filter(mutant -> only.map(mutant.id()::equals).orElse(true))
            .toList();
    if (only.isPresent() && mutants.isEmpty()) {
      throw new UnknownMutant(only.get(), projectName);
    }
    return mutants;
  }

  private static Map<String, byte[]> instrumentMutated(
      Map<String, ClassModel> models, List<Mutant> mutants, Coverage coverage) {
    var mutated = classesToRun(mutants);
    var instrumented = new LinkedHashMap<String, byte[]>();
    models.forEach(
        (name, model) -> {
          if (mutated.contains(name)) {
            instrumented.put(name, coverage.instrument(model));
          }
        });
    return instrumented;
  }

  /// The classes with a mutant that isn't skipped, by binary name.
  private static java.util.Set<String> classesToRun(List<Mutant> mutants) {
    return mutants.stream()
        .filter(mutant -> mutant.skipped().isEmpty())
        .map(Mutant::binaryName)
        .collect(Collectors.toSet());
  }

  /// A worker for each mutant to run side by side, up to `wanted`; none has started yet.
  private List<TestWorker> workersFor(Project project, int wanted, int mutantCount) {
    var workers = new ArrayList<TestWorker>();
    for (int number = 1; number <= Math.min(wanted, Math.max(1, mutantCount)); number++) {
      workers.add(new TestWorker(project, workerJar, number == 1 ? log : numbered(log, number)));
    }
    return workers;
  }

  /// Every test once, on `first`, with coverage; flaky tests are left out of what it recorded. A
  /// module with no test classes has nothing to run: every mutant is not covered, and no worker
  /// starts (one couldn't, with no JUnit on the classpath).
  private CoveragePass coveragePass(Project project, Plan plan, TestWorker first) {
    var testClasses = testClassesIn(project.testClasses());
    if (testClasses.isEmpty()) {
      return new CoveragePass(0, 0, Duration.ZERO, plan.mutants().size(), List.of());
    }
    var coverage = plan.mutantRun().coverage();
    long coverageStart = System.nanoTime();
    var baseline = first.cover(plan.instrumented(), testClasses, coverage);
    var flaky =
        baseline.passed() ? List.<String>of() : flakyOrFail(baseline.failing(), coverage, first);
    coverage.exclude(flaky);
    var coverageTook = Duration.ofNanos(System.nanoTime() - coverageStart);
    if (coverage.testCount() == 0) {
      throw new IllegalStateException(
          "no tests ran in " + project.name() + "; mutastic runs JUnit Platform tests");
    }
    return new CoveragePass(
        first.jdk(), coverage.testCount(), coverageTook, plan.mutants().size(), flaky);
  }

  /// Every mutant, on the workers side by side, in the order they were found. A catch that a port
  /// in use caused, or a timeout, may be the other workers' doing: those mutants run again
  /// afterwards on one worker, with the others stopped, and are reported then.
  private static final class SideBySide {
    private final List<Mutant> mutants;
    private final MutantRun mutantRun;
    private final List<TestWorker> workers;
    private final Listener listener;
    private final MutantResult[] results;
    private final SourceFiles files;

    /// Mutants whose result side by side may be another worker's doing, to run again alone.
    private final java.util.Queue<Integer> again =
        new java.util.concurrent.ConcurrentLinkedQueue<>();

    private final java.util.concurrent.BlockingQueue<TestWorker> idle;
    private final Object report = new Object();

    SideBySide(
        List<Mutant> mutants, MutantRun mutantRun, List<TestWorker> workers, Listener listener) {
      this.mutants = mutants;
      this.mutantRun = mutantRun;
      this.workers = workers;
      this.listener = listener;
      this.results = new MutantResult[mutants.size()];
      this.files = new SourceFiles(mutants);
      this.idle = new java.util.concurrent.LinkedBlockingQueue<>(workers);
    }

    List<MutantResult> runAll() {
      try (var executor =
          java.util.concurrent.Executors.newFixedThreadPool(
              workers.size(), Thread.ofPlatform().name("mutastic-", 1).daemon().factory())) {
        var futures = new ArrayList<java.util.concurrent.Future<?>>();
        for (int index = 0; index < mutants.size(); index++) {
          int at = index;
          futures.add(executor.submit(() -> runOnIdleWorker(at)));
        }
        for (var future : futures) {
          await(future);
        }
      }
      if (!again.isEmpty()) {
        runAgainAlone();
      }
      return List.of(results);
    }

    private void runOnIdleWorker(int at) {
      var worker = take(idle);
      try {
        var result = mutantRun.run(mutants.get(at), worker);
        if (workers.size() > 1 && mayBeContention(result)) {
          again.add(at);
          return;
        }
        results[at] = result.result();
        synchronized (report) {
          listener.mutantFinished(result.result());
          files.finished(at, results, listener);
        }
      } finally {
        idle.add(worker);
      }
    }

    /// A port in use may be another worker's test holding it. A timeout may be the workers
    /// competing (for CPU, a lock, a database, each starting a Spring context) while the limit came
    /// from a coverage pass that ran alone: a timeout counts as a catch, so it must be the
    /// mutant's.
    private static boolean mayBeContention(MutantRun.Outcome outcome) {
      return outcome.portClash() || outcome.result().status() == Status.TIMED_OUT;
    }

    private void runAgainAlone() {
      workers.stream().skip(1).forEach(TestWorker::close); // nothing else competes now
      for (int at : again.stream().sorted().toList()) {
        results[at] = mutantRun.run(mutants.get(at), workers.getFirst()).result();
        listener.mutantFinished(results[at]);
        files.finished(at, results, listener);
      }
    }
  }

  /// Which mutants belong to each source file (a top-level class and its nested classes), to say
  /// when a file's last one is done.
  private static final class SourceFiles {
    private final List<String> fileOf = new ArrayList<>();
    private final Map<String, List<Integer>> members = new LinkedHashMap<>();
    private final Map<String, Integer> remaining = new java.util.HashMap<>();

    SourceFiles(List<Mutant> mutants) {
      for (int at = 0; at < mutants.size(); at++) {
        var name = mutants.get(at).binaryName();
        int dollar = name.indexOf('$', name.lastIndexOf('.') + 1);
        var file = dollar < 0 ? name : name.substring(0, dollar);
        fileOf.add(file);
        members.computeIfAbsent(file, ignored -> new ArrayList<>()).add(at);
        remaining.merge(file, 1, Integer::sum);
      }
    }

    /// Mutant `at` has its final result; called with the caller holding the listener's lock.
    void finished(int at, MutantResult[] results, Listener listener) {
      var file = fileOf.get(at);
      if (remaining.merge(file, -1, Integer::sum) == 0) {
        listener.fileFinished(
            file, requireNonNull(members.get(file)).stream().map(index -> results[index]).toList());
      }
    }
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
    return log.resolveSibling(
        dot < 0
            ? name + "-" + number
            : name.substring(0, dot) + "-" + number + name.substring(dot));
  }

  /// How many times a test that failed the coverage pass is run again on its own before it counts
  /// as failing. One rerun wasn't enough: on spec-tictactoe a test that fails about half the time
  /// under mutastic failed its single rerun too in 1 run of 10, stopping the run.
  static final int RERUNS = 3;

  /// The tests that failed in the coverage pass, if every one passes when run again on its own
  /// (within [#RERUNS] tries): they're flaky, and the run goes on without them. A test that fails
  /// every try means the tests are red, and nothing a mutant does can be judged against them.
  ///
  /// A flaky test left in would fail at random against mutants too, and its failures would count as
  /// catches it never made.
  private List<String> flakyOrFail(List<String> failing, Coverage coverage, TestWorker worker) {
    // A test class that failed as a whole (a `@BeforeAll`) is reported by its unique id, which
    // can't be run again on its own.
    boolean rerunnable = failing.stream().allMatch(test -> test.contains("#"));
    var stillFailing =
        rerunnable
            ? failing.stream()
                .filter(test -> !passesWithin(RERUNS, test, coverage, worker))
                .toList()
            : failing;
    if (!stillFailing.isEmpty()) {
      throw new BaselineFailed(stillFailing, log);
    }
    return failing;
  }

  private static boolean passesWithin(
      int tries, String test, Coverage coverage, TestWorker worker) {
    for (int attempt = 0; attempt < tries; attempt++) {
      if (worker.passes(test, timeoutFor(coverage.timeOf(test)))) {
        return true;
      }
    }
    return false;
  }

  private static Duration timeoutFor(Duration normal) {
    return Duration.ofNanos((long) (normal.toNanos() * TIMEOUT_FACTOR)).plus(TIMEOUT_ALLOWANCE);
  }

  /// What one mutant needs from the run, shared by every worker's thread. Applying and verifying a
  /// mutant share the ClassFile context's caches, so they take turns; only the tests run side by
  /// side. Coverage is only read once the coverage pass is over.
  private record MutantRun(
      Map<String, ClassModel> models,
      Mutations mutations,
      Coverage coverage,
      boolean confirmCatches,
      boolean suggestions) {
    /// At most this many sets of arguments to try per mutant.
    static final int CANDIDATES = 400;

    record Outcome(MutantResult result, boolean portClash) {}

    /// The class a mutant was found in: every mutant comes from one of these models.
    private ClassModel model(Mutant mutant) {
      return requireNonNull(models.get(mutant.binaryName()), mutant.binaryName());
    }

    Outcome run(Mutant mutant, TestWorker worker) {
      if (mutant.skipped().isPresent()) {
        return new Outcome(MutantResult.skipped(mutant), false);
      }
      var covering = coverage.coveringTests(mutant);
      if (covering.isEmpty()) {
        return new Outcome(MutantResult.notCovered(mutant), false);
      }
      var trial = new Trial(worker, mutant, verifiedMutation(mutant), coverage);
      var tested = testConfirmed(trial, covering);
      var inputs = coverage.inputs(mutant, covering);
      var result = resultOf(trial, covering, tested, inputs);
      if (suggestions && tested.run().status() == Status.MISSED) {
        var receiver = receiverFor(mutant, covering.getFirst());
        if (receiver.isPresent()) {
          result = result.withSuggestion(suggest(receiver.get(), trial, inputs));
        }
      }
      return new Outcome(result, tested.run().portClash());
    }

    /// The mutant's class, mutated and checked to load.
    private byte[] verifiedMutation(Mutant mutant) {
      synchronized (mutations) {
        var bytes = mutations.apply(model(mutant), mutant);
        var errors = mutations.verify(bytes);
        if (!errors.isEmpty()) {
          // A mutant that won't load proves nothing, and counting it as caught would flatter the
          // score: this is mutastic's bug, not the user's.
          throw new IllegalStateException(
              "mutant " + mutant.id() + " doesn't verify: " + errors.getFirst().getMessage());
        }
        return bytes;
      }
    }

    /// The covering tests' run, and the catcher whose catch didn't repeat, if one didn't.
    private record Tested(TestWorker.Run run, Optional<String> unconfirmed) {}

    private Tested testConfirmed(Trial trial, List<String> covering) {
      var run = trial.run(covering);
      if (!confirmCatches || run.status() != Status.CAUGHT) {
        return new Tested(run, Optional.empty());
      }
      var catcher = run.failedTest().orElseThrow();
      if (trial.run(List.of(catcher)).status() != Status.MISSED) {
        return new Tested(run, Optional.empty());
      }
      // Not a catch: the tests after it decide, as if it had passed the first time.
      var rest = covering.subList(covering.indexOf(catcher) + 1, covering.size());
      int ran = run.testsRun();
      var after = rest.isEmpty() ? new TestWorker.Run(Status.MISSED, 0) : trial.run(rest);
      return new Tested(
          new TestWorker.Run(
              after.status(),
              after.failedTest(),
              after.failure(),
              after.origin(),
              ran + after.testsRun(),
              after.portClash()),
          Optional.of(catcher));
    }

    private static MutantResult resultOf(
        Trial trial, List<String> covering, Tested tested, Map<String, List<Long>> inputs) {
      var run = tested.run();
      return new MutantResult(
          trial.mutant(),
          run.status(),
          covering,
          run.failedTest(),
          run.failure(),
          run.origin(),
          run.testsRun(),
          tested.unconfirmed(),
          inputs,
          Optional.empty(),
          trial.took());
    }

    /// How the test class a suggestion would go into, the fastest covering test's, could write its
    /// call: `-` for a static method, else the constructor for the receiver. Empty if it can't.
    private Optional<String> receiverFor(Mutant mutant, String fastestTest) {
      int hash = fastestTest.indexOf('#');
      var testClass = hash < 0 ? fastestTest : fastestTest.substring(0, hash);
      synchronized (mutations) {
        return Access.receiver(model(mutant), mutant, testClass);
      }
    }

    /// Each candidate is the receiver's constructor arguments, if any, then the method's.
    private Optional<Suggestion> suggest(
        String receiver, Trial trial, Map<String, List<Long>> inputs) {
      var mutant = trial.mutant();
      var shape = CallShape.of(mutant, receiver);
      if (!shape.takesWholeNumbers()) {
        return Optional.empty();
      }
      var values = candidateValues(mutant, inputs);
      var candidates = new ArrayList<List<Long>>();
      combine(List.copyOf(values), shape.arity(), new ArrayList<>(), candidates);
      return trial
          .worker()
          .suggest(mutant, receiver, trial.bytes(), candidates)
          .map(found -> suggestion(shape, found));
    }

    /// The constants near the mutant's site and either side of each, the values the tests passed,
    /// then a few small numbers.
    private java.util.SequencedSet<Long> candidateValues(
        Mutant mutant, Map<String, List<Long>> inputs) {
      List<Long> constants;
      synchronized (mutations) {
        constants = mutations.constantsNear(model(mutant), mutant);
      }
      var values = new java.util.LinkedHashSet<Long>();
      constants.forEach(constant -> values.addAll(List.of(constant, constant - 1, constant + 1)));
      inputs.values().forEach(values::addAll);
      values.addAll(List.of(0L, 1L, 2L, 3L, -1L, 10L));
      return values;
    }

    /// The suggestion from the worker's `found`: the arguments, then what the method returns today
    /// and with the mutant.
    private Suggestion suggestion(CallShape shape, String[] found) {
      var all = found[0].equals("-") ? List.<Long>of() : wholeNumbers(found[0]);
      int receiverCount = shape.constructor().parameterCount();
      var receiverArguments = all.subList(0, receiverCount);
      var arguments = all.subList(receiverCount, all.size());
      var mutant = shape.mutant();
      var names = coverage.parameterNames(mutant);
      if (names.size() != arguments.size()) {
        names = placeholderNames(arguments.size());
      }
      var simple = mutant.className().substring(mutant.className().lastIndexOf('/') + 1);
      return new Suggestion(
          shape.call(receiverArguments, arguments),
          names,
          arguments,
          simple.substring(simple.lastIndexOf('$') + 1),
          receiverArguments,
          found[1],
          found[2]);
    }

    /// `1;2` as its numbers.
    private static List<Long> wholeNumbers(String values) {
      return java.util.Arrays.stream(values.split(";", -1)).map(Long::valueOf).toList();
    }

    /// `arg0`, `arg1` and so on, for parameters with no names in the class file.
    private static List<String> placeholderNames(int count) {
      return java.util.stream.IntStream.range(0, count).mapToObj(index -> "arg" + index).toList();
    }

    /// How a suggestion calls the mutant's method: `receiver` is `-` for a static method, else the
    /// descriptor of the constructor that makes the receiver.
    private record CallShape(
        Mutant mutant,
        String receiver,
        java.lang.constant.MethodTypeDesc constructor,
        java.lang.constant.MethodTypeDesc type) {
      static CallShape of(Mutant mutant, String receiver) {
        var type = java.lang.constant.MethodTypeDesc.ofDescriptor(mutant.descriptor());
        var constructor =
            receiver.equals("-")
                ? java.lang.constant.MethodTypeDesc.of(java.lang.constant.ConstantDescs.CD_void)
                : java.lang.constant.MethodTypeDesc.ofDescriptor(receiver);
        return new CallShape(mutant, receiver, constructor, type);
      }

      /// Whether every parameter of the method takes a number a suggestion can fill.
      boolean takesWholeNumbers() {
        return type.parameterList().stream()
            .allMatch(parameter -> Access.WHOLE_NUMBERS.contains(parameter.descriptorString()));
      }

      /// How many values a candidate has: the constructor's, then the method's.
      int arity() {
        return constructor.parameterCount() + type.parameterCount();
      }

      /// `Pricing.discount(100)` for a static method, `new Pricing().discount(100)`, or `new
      /// GoldenScorer.Score(1, 1).hitAt(1)` when the receiver needs arguments.
      String call(List<Long> receiverArguments, List<Long> arguments) {
        var simple =
            mutant.className().substring(mutant.className().lastIndexOf('/') + 1).replace('$', '.');
        var on =
            receiver.equals("-")
                ? simple
                : "new %s(%s)".formatted(simple, literals(constructor, receiverArguments));
        return "%s.%s(%s)".formatted(on, mutant.methodName(), literals(type, arguments));
      }
    }

    /// `1, 2L`: each value as Java source for its parameter (`long` and `Long` take an `L`).
    private static String literals(java.lang.constant.MethodTypeDesc type, List<Long> values) {
      var written = new ArrayList<String>();
      for (int index = 0; index < values.size(); index++) {
        var descriptor = type.parameterType(index).descriptorString();
        written.add(
            values.get(index)
                + (descriptor.equals("J") || descriptor.equals("Ljava/lang/Long;") ? "L" : ""));
      }
      return String.join(", ", written);
    }

    /// Every way to pick one of `values` per parameter, the earlier values first, up to
    /// [#CANDIDATES].
    private static void combine(
        List<Long> values, int arity, List<Long> prefix, List<List<Long>> out) {
      if (out.size() >= CANDIDATES) {
        return;
      }
      if (prefix.size() == arity) {
        out.add(List.copyOf(prefix));
        return;
      }
      for (Long value : values) {
        prefix.add(value);
        combine(values, arity, prefix, out);
        prefix.removeLast();
      }
    }
  }

  /// A mutant's class on one worker, timed from when its tests can start.
  private static final class Trial {
    private final TestWorker worker;
    private final Mutant mutant;
    private final byte[] bytes;
    private final Coverage coverage;
    private final long start = System.nanoTime();

    Trial(TestWorker worker, Mutant mutant, byte[] bytes, Coverage coverage) {
      this.worker = worker;
      this.mutant = mutant;
      this.bytes = bytes;
      this.coverage = coverage;
    }

    /// `tests` against the mutant, in order until one fails, each allowed a little over its time in
    /// the coverage pass.
    TestWorker.Run run(List<String> tests) {
      return worker.run(mutant.binaryName(), bytes, tests, timeoutFor(coverage.timeOf(tests)));
    }

    TestWorker worker() {
      return worker;
    }

    Mutant mutant() {
      return mutant;
    }

    byte[] bytes() {
      return bytes;
    }

    Duration took() {
      return Duration.ofNanos(System.nanoTime() - start);
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
        var name =
            root.relativize(file)
                .toString()
                .replace(java.io.File.separatorChar, '.')
                .replaceAll("\\.class$", "");
        if (name.indexOf('$') < 0
            && !name.endsWith("module-info")
            && !name.endsWith("package-info")) {
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
