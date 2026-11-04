package io.mutastic.engine;

import static java.util.Objects.requireNonNull;

import io.mutastic.api.ChangedLines;
import io.mutastic.api.CoveragePass;
import io.mutastic.api.Ignore;
import io.mutastic.api.Listener;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Policy;
import io.mutastic.api.Project;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import io.mutastic.api.Suggestion;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
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

    /// @param services the JDK services the project provides that the worker can't install: each a
    ///   likely reason tests that pass in the build fail here
    BaselineFailed(List<String> failing, Path log, List<String> services) {
      super(
          "the tests fail without any mutant: "
              + String.join(", ", failing)
              + "; their output is in "
              + log
              + loadedElsewhere(failing, services));
      this.failing = List.copyOf(failing);
    }

    /// Why tests that pass in the build can fail here, when the project provides a JDK service.
    private static String loadedElsewhere(List<String> failing, List<String> services) {
      if (services.isEmpty()) {
        return "";
      }
      var classes =
          failing.stream()
              .map(test -> test.replaceFirst("#.*", ""))
              .map(test -> test.substring(test.lastIndexOf('.') + 1))
              .distinct()
              .map(name -> "--exclude-tests=" + name)
              .collect(Collectors.joining(" "));
      return ("%nIf they pass in the build, this is likely why: the project provides %s in"
              + " META-INF/services, which the JDK looks for on the system class path. mutastic"
              + " loads the project in class loaders of its own, so the JDK doesn't find it here."
              + " To run without those tests: %s")
          .formatted(String.join(" and ", services), classes);
    }

    public List<String> failing() {
      return failing;
    }
  }

  /// What a run does beyond finding and running every mutant.
  ///
  /// @param only run just the mutants with these ids, each of which must be there; every mutant
  ///   when empty
  /// @param confirmCatches run a catching test once more against its mutant: a catch that doesn't
  ///   repeat came from a flaky test, and the mutant's other covering tests decide. One more test
  ///   run per catch.
  /// @param workers how many worker JVMs run mutants side by side
  /// @param suggestions look for a test that catches each missed mutant, by calling its method with
  ///   candidate inputs, today and with the mutant: user code runs outside its tests
  ///
  /// @param auditArid run the mutants the arid filters skip anyway, and report what happened to
  ///   them apart: what the filters hide, and whether any of it was caught
  /// @param among only these mutants, by id, when not empty: those an earlier run left uncaught
  /// @param policy the project's own rules and filters
  /// @param changed the lines of a change, when the run is scoped to it: no other mutant runs
  public record Options(
      List<String> only,
      boolean confirmCatches,
      int workers,
      boolean suggestions,
      boolean auditArid,
      java.util.Set<String> among,
      Policy policy,
      Optional<ChangedLines> changed) {
    /// The same options, for only the mutants with these ids.
    public Options among(java.util.Set<String> ids) {
      return new Options(
          only, confirmCatches, workers, suggestions, auditArid, ids, policy, changed);
    }

    public Options {
      only = List.copyOf(only);
      among = java.util.Set.copyOf(among);
      if (workers < 1) {
        throw new IllegalArgumentException("workers must be at least 1, not " + workers);
      }
    }
  }

  /// Runs the mutants. Each result goes to `finished` as soon as it's known.
  public Report run(Project project, Options options, Listener listener) {
    long start = System.nanoTime();
    var plan = plan(project, options);
    var workers = workersFor(project, options.workers(), plan.mutants().size());
    try {
      var pass = coveragePass(project, plan, workers.getFirst());
      listener.coverageFinished(pass);
      var results = new SideBySide(plan.mutants(), plan.mutantRun(), workers, listener).runAll();
      return new Report(
          project.name(),
          workers.getFirst().jdk(),
          results,
          plan.mutantRun().coverage().testCount(),
          pass.flaky(),
          pass.took(),
          // The workers that ran something: a run with fewer mutants to run than workers leaves
          // some unstarted, and "4 workers (-1 replaced)" said so wrongly.
          (int) Math.max(1, workers.stream().filter(worker -> worker.started() > 0).count()),
          workers.stream().mapToInt(TestWorker::started).sum(),
          Duration.ofNanos(System.nanoTime() - start),
          inOrderFound(plan.mutantRun().audited(), plan.mutants()),
          options.changed().map(changed -> changed.within(project.sources())),
          plan.stale());
    } finally {
      workers.forEach(TestWorker::close);
    }
  }

  /// A run's mutants and what running them needs, before any test has run.
  ///
  /// @param instrumented each class with a mutant to run, instrumented for the coverage pass
  /// @param stale the project's ignores that no longer hold
  private record Plan(
      List<Mutant> mutants,
      MutantRun mutantRun,
      Map<String, byte[]> instrumented,
      List<Ignore> stale) {}

  /// A project's mutants as a run would have them, and the ignores that no longer hold.
  public record Mutants(List<Mutant> all, List<Ignore> stale) {}

  /// The mutants a run would have, as it would have them, without running a test: for `ignore` and
  /// `explain`.
  public Mutants find(Project project, Options options) {
    var context = contextFor(project);
    var found = found(project, options, context, classesIn(project.classes(), context));
    return new Mutants(found.mutants(), found.stale());
  }

  /// The mutants found, the filters that judged them, and the ignores that no longer hold.
  private record Found(List<Mutant> mutants, Mutations mutations, List<Ignore> stale) {}

  private static Found found(
      Project project, Options options, ClassFile context, Map<String, ClassModel> models) {
    var mutations = new Mutations(context, options.policy());
    mutations.prepare(models.values());
    var mutants =
        mutantsIn(models, mutations, options.only(), project.name()).stream()
            .filter(mutant -> options.among().isEmpty() || options.among().contains(mutant.id()))
            .filter(inScope(models, project, options.changed()))
            .toList();
    var ignored = new Ignored(options.policy().ignores(), models, project);
    boolean complete =
        options.only().isEmpty() && options.among().isEmpty() && options.changed().isEmpty();
    return new Found(ignored.apply(mutants, complete), mutations, ignored.stale());
  }

  private static Plan plan(Project project, Options options) {
    var context = contextFor(project);
    var models = classesIn(project.classes(), context);
    var found = found(project, options, context, models);
    var mutants = found.mutants();
    var mutations = found.mutations();
    var coverage = new Coverage(context);
    var instrumented = instrumentMutated(models, mutants, coverage, options.auditArid());
    var mutantRun =
        new MutantRun(
            models,
            mutations,
            coverage,
            options.confirmCatches(),
            options.suggestions(),
            options.auditArid(),
            java.util.Collections.synchronizedList(new ArrayList<>()));
    return new Plan(mutants, mutantRun, instrumented, found.stale());
  }

  /// Classes resolve from the project's own, its tests' and its dependencies'.
  private static ClassFile contextFor(Project project) {
    return ClassFiles.resolvingFrom(
        Stream.of(project.classes(), project.testClasses(), project.dependencies())
            .flatMap(List::stream)
            .toList());
  }

  /// Every mutant in the classes, or only those with the ids `only`, which must all be there.
  private static List<Mutant> mutantsIn(
      Map<String, ClassModel> models, Mutations mutations, List<String> only, String projectName) {
    var mutants =
        models.values().stream()
            .flatMap(model -> mutations.find(model).stream())
            .filter(mutant -> only.isEmpty() || only.contains(mutant.id()))
            .toList();
    var found = mutants.stream().map(Mutant::id).collect(Collectors.toSet());
    only.stream()
        .filter(id -> !found.contains(id))
        .findFirst()
        .ifPresent(
            id -> {
              throw new UnknownMutant(id, projectName);
            });
    return mutants;
  }

  private static Map<String, byte[]> instrumentMutated(
      Map<String, ClassModel> models, List<Mutant> mutants, Coverage coverage, boolean auditArid) {
    var mutated = classesToRun(mutants, auditArid);
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
  private static java.util.Set<String> classesToRun(List<Mutant> mutants, boolean auditArid) {
    return mutants.stream()
        .filter(mutant -> mutant.skipped().isEmpty() || (auditArid && isArid(mutant)))
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
    var testClasses =
        testClassesIn(project.testClasses()).stream().filter(project.tests()::runs).toList();
    if (testClasses.isEmpty()) {
      return new CoveragePass(0, 0, Duration.ZERO, plan.mutants().size(), List.of());
    }
    var coverage = plan.mutantRun().coverage();
    long coverageStart = System.nanoTime();
    var baseline = first.cover(plan.instrumented(), testClasses, coverage);
    var flaky =
        baseline.passed()
            ? List.<String>of()
            : flakyOrFail(baseline.failing(), coverage, first, project);
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
  private List<String> flakyOrFail(
      List<String> failing, Coverage coverage, TestWorker worker, Project project) {
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
      throw new BaselineFailed(stillFailing, log, SystemServices.providedBy(project));
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

  /// Whether a mutant is on a changed line, when the run is scoped to a change.
  private static java.util.function.Predicate<Mutant> inScope(
      Map<String, ClassModel> models, Project project, Optional<ChangedLines> changed) {
    if (changed.isEmpty()) {
      return mutant -> true;
    }
    var files = new java.util.HashMap<String, Optional<Path>>();
    return mutant -> {
      var file =
          files.computeIfAbsent(
              mutant.binaryName(),
              name ->
                  Optional.ofNullable(models.get(name))
                      .flatMap(model -> SourcePaths.of(model, project.sources())));
      return file.filter(path -> changed.get().covers(path, mutant.line())).isPresent();
    };
  }

  /// The results in the order their mutants were found, whatever order they finished in.
  private static List<MutantResult> inOrderFound(List<MutantResult> results, List<Mutant> mutants) {
    var order = new java.util.HashMap<String, Integer>();
    for (int at = 0; at < mutants.size(); at++) {
      order.put(mutants.get(at).fingerprint(), at);
    }
    synchronized (results) {
      return results.stream()
          .sorted(
              java.util.Comparator.comparing(
                  result -> order.getOrDefault(result.mutant().fingerprint(), 0)))
          .toList();
    }
  }

  private static boolean isArid(Mutant mutant) {
    return mutant.skipped().filter(reason -> reason.startsWith("arid:")).isPresent();
  }

  /// What one mutant needs from the run, shared by every worker's thread. Applying and verifying a
  /// mutant share the ClassFile context's caches, so they take turns; only the tests run side by
  /// side. Coverage is only read once the coverage pass is over.
  ///
  /// @param audited each arid mutant's result when run anyway, in an audit
  private record MutantRun(
      Map<String, ClassModel> models,
      Mutations mutations,
      Coverage coverage,
      boolean confirmCatches,
      boolean suggestions,
      boolean auditArid,
      List<MutantResult> audited) {
    /// At most this many sets of arguments to try per mutant.
    static final int CANDIDATES = 400;

    record Outcome(MutantResult result, boolean portClash) {}

    /// The class a mutant was found in: every mutant comes from one of these models.
    private ClassModel model(Mutant mutant) {
      return requireNonNull(models.get(mutant.binaryName()), mutant.binaryName());
    }

    Outcome run(Mutant mutant, TestWorker worker) {
      if (mutant.skipped().isPresent()) {
        if (auditArid && isArid(mutant)) {
          audited.add(run(mutant.unskipped(), worker).result());
        }
        return new Outcome(MutantResult.skipped(mutant), false);
      }
      var covering = coverage.coveringTests(mutant);
      if (covering.isEmpty()) {
        return new Outcome(uncovered(mutant, worker), false);
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

    /// A mutant no test reaches, with the first test to write for it when suggestions are on.
    private MutantResult uncovered(Mutant mutant, TestWorker worker) {
      var result = MutantResult.notCovered(mutant);
      return suggestions ? result.withSuggestion(startingPoint(mutant, worker)) : result;
    }

    /// For a mutant no test reaches, the first test to write: a call a test in the class's own
    /// package could make, and its result, where the mutant changes it.
    private Optional<Suggestion> startingPoint(Mutant mutant, TestWorker worker) {
      Optional<String> receiver;
      synchronized (mutations) {
        receiver = Access.receiver(models, mutant, mutant.binaryName() + "Test");
      }
      if (receiver.isEmpty()) {
        return Optional.empty();
      }
      var trial = new Trial(worker, mutant, verifiedMutation(mutant), coverage);
      return suggest(receiver.get(), trial, Map.of());
    }

    /// How the test class a suggestion would go into, the fastest covering test's, could write its
    /// call: `-` for a static method, else the constructor for the receiver. Empty if it can't.
    private Optional<String> receiverFor(Mutant mutant, String fastestTest) {
      int hash = fastestTest.indexOf('#');
      var testClass = hash < 0 ? fastestTest : fastestTest.substring(0, hash);
      synchronized (mutations) {
        return Access.receiver(models, mutant, testClass);
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
      int receiverCount = shape.receiverArity();
      var receiverArguments = all.subList(0, receiverCount);
      var arguments = all.subList(receiverCount, all.size());
      var mutant = shape.mutant();
      var names = coverage.parameterNames(mutant);
      if (names.size() != arguments.size()) {
        names = placeholderNames(arguments.size());
      }
      return new Suggestion(
          shape.call(receiverArguments, arguments),
          names,
          arguments,
          shape.instances(receiverArguments),
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

    /// How a suggestion calls the mutant's method: on no receiver for a static method, else on one
    /// made by each constructor of `receiver` in turn, outermost first.
    private record CallShape(Mutant mutant, List<Made> receiver, MethodTypeDesc type) {
      /// One constructor call: `new Shipping(10)`, or `.new Parcel(30)` for an inner class, made
      /// inside the instance before it.
      private record Made(String className, MethodTypeDesc constructor, boolean inner) {
        /// The parameters a suggestion fills: all but an inner class's outer instance.
        List<ClassDesc> parameters() {
          var all = constructor.parameterList();
          return inner ? all.subList(1, all.size()) : all;
        }

        /// `Parcel` for `com/example/Shipping$Parcel`: the name an inner class is made by.
        String simpleName() {
          return className.substring(
              Math.max(className.lastIndexOf('/'), className.lastIndexOf('$')) + 1);
        }

        String written(List<Long> arguments) {
          var nested = className.substring(className.lastIndexOf('/') + 1).replace('$', '.');
          return (inner ? ".new %s(%s)" : "new %s(%s)")
              .formatted(inner ? simpleName() : nested, literals(parameters(), arguments));
        }
      }

      /// `receiver` is `-` for a static method, else constructor descriptors joined by `>`, as
      /// [Access#receiver] gives them. Each inner class's outer class is its constructor's first
      /// parameter.
      static CallShape of(Mutant mutant, String receiver) {
        var type = MethodTypeDesc.ofDescriptor(mutant.descriptor());
        var made = new ArrayList<Made>();
        if (!receiver.equals("-")) {
          var descriptors = receiver.split(">", -1);
          var className = mutant.className();
          for (int index = descriptors.length - 1; index >= 0; index--) {
            var constructor = MethodTypeDesc.ofDescriptor(descriptors[index]);
            made.addFirst(new Made(className, constructor, index > 0));
            if (index > 0) {
              var outer = constructor.parameterType(0).descriptorString();
              className = outer.substring(1, outer.length() - 1);
            }
          }
        }
        return new CallShape(mutant, List.copyOf(made), type);
      }

      /// Whether every parameter of the method takes a number a suggestion can fill.
      boolean takesWholeNumbers() {
        return type.parameterList().stream()
            .allMatch(parameter -> Access.WHOLE_NUMBERS.contains(parameter.descriptorString()));
      }

      /// The instances the call makes, with their arguments.
      List<Suggestion.Instance> instances(List<Long> receiverArguments) {
        var each = eachArguments(receiverArguments);
        return java.util.stream.IntStream.range(0, receiver.size())
            .mapToObj(
                index -> new Suggestion.Instance(receiver.get(index).simpleName(), each.get(index)))
            .toList();
      }

      /// `receiverArguments` split into each constructor's.
      private List<List<Long>> eachArguments(List<Long> receiverArguments) {
        var each = new ArrayList<List<Long>>();
        int next = 0;
        for (Made made : receiver) {
          int count = made.parameters().size();
          each.add(receiverArguments.subList(next, next + count));
          next += count;
        }
        return each;
      }

      /// How many values making the receiver takes.
      int receiverArity() {
        return receiver.stream().mapToInt(made -> made.parameters().size()).sum();
      }

      /// How many values a candidate has: the receiver's, then the method's.
      int arity() {
        return receiverArity() + type.parameterCount();
      }

      /// `Pricing.discount(100)` for a static method, `new Pricing().discount(100)`, `new
      /// GoldenScorer.Score(1, 1).hitAt(1)` when the receiver needs arguments, or `new
      /// Shipping(10).new Parcel(30).heavy()` for an inner class.
      String call(List<Long> receiverArguments, List<Long> arguments) {
        var on = new StringBuilder();
        if (receiver.isEmpty()) {
          var className = mutant.className();
          on.append(className.substring(className.lastIndexOf('/') + 1).replace('$', '.'));
        }
        var each = eachArguments(receiverArguments);
        for (int index = 0; index < receiver.size(); index++) {
          on.append(receiver.get(index).written(each.get(index)));
        }
        return "%s.%s(%s)"
            .formatted(on, mutant.methodName(), literals(type.parameterList(), arguments));
      }
    }

    /// `1, 2L`: each value as Java source for its parameter (`long` and `Long` take an `L`).
    private static String literals(List<ClassDesc> types, List<Long> values) {
      var written = new ArrayList<String>();
      for (int index = 0; index < values.size(); index++) {
        var descriptor = types.get(index).descriptorString();
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
      var timeout = timeoutFor(coverage.timeOf(tests));
      return coverage.onlyAtLoad(mutant)
          ? worker.runFresh(mutant.binaryName(), bytes, tests, timeout)
          : worker.run(mutant.binaryName(), bytes, tests, timeout);
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
