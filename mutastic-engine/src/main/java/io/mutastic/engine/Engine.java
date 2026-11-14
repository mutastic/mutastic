package io.mutastic.engine;

import static java.util.Objects.requireNonNull;

import io.mutastic.api.ChangedLines;
import io.mutastic.api.ClassMutator;
import io.mutastic.api.CoveragePass;
import io.mutastic.api.FlakyTest;
import io.mutastic.api.Ghost;
import io.mutastic.api.Ignore;
import io.mutastic.api.Listener;
import io.mutastic.api.Mutant;
import io.mutastic.api.MutantCost;
import io.mutastic.api.MutantResult;
import io.mutastic.api.Nearest;
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
import org.jspecify.annotations.Nullable;

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

  /// The run can't go on as asked, for a reason in what it was asked or in the project's setup, not
  /// in mutastic or the tests: a usage error, to the command line.
  public abstract static class CantRun extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    CantRun(String message) {
      super(message);
    }
  }

  /// `--only` named a mutant the project doesn't have.
  public static final class UnknownMutant extends CantRun {
    @java.io.Serial private static final long serialVersionUID = 1L;

    private final String id;
    private final @Nullable String closest;

    UnknownMutant(String id, String projectName, Optional<String> closest) {
      super(
          closest
              .map(
                  close ->
                      "no mutant %s in %s. There's one called %s."
                          .formatted(id, projectName, close))
              .orElseGet(
                  () ->
                      ("no mutant %s in %s. A mutant's id belongs to the project its run was on: "
                              + "run the command as that run printed it, with the project's"
                              + " directory and module")
                          .formatted(id, projectName)));
      this.id = id;
      this.closest = closest.orElse(null);
    }

    /// The id as it was asked for.
    public String id() {
      return id;
    }

    /// The project's one id a slip away from it, if there's one: said, never run in its place.
    public Optional<String> closest() {
      return Optional.ofNullable(closest);
    }
  }

  /// The module has test classes, but none ran on the JUnit Platform: a runner mutastic doesn't
  /// run, or tests every one of which is filtered out.
  public static final class NoTestsRan extends CantRun {
    @java.io.Serial private static final long serialVersionUID = 1L;

    NoTestsRan(Project project) {
      super(
          ("no test ran in %s, so there's nothing to judge a mutant by. mutastic runs tests on the"
                  + " JUnit Platform: JUnit 5, and JUnit 4 and TestNG through their engines, as"
                  + " the build's test task picks them")
              .formatted(project.name()));
    }
  }

  /// The tests ran and passed, but the coverage pass recorded no line of any class with a mutant:
  /// it measured nothing, so no score can be given.
  ///
  /// Seen with JaCoCo's agent and `quarkus-junit5`: JaCoCo's transformer, registered when the JVM
  /// starts, rewrites each class before the worker's, which framework mode registers later, sees
  /// it. The worker then takes the class for one the framework rewrote and leaves it alone, so its
  /// instrumented copy is never defined and no probe runs. Without the framework, the worker
  /// defines that copy itself, and JaCoCo's probes only add to its own.
  public static final class NothingCovered extends CantRun {
    @java.io.Serial private static final long serialVersionUID = 1L;

    NothingCovered(Project project, int tests, int classes, Path log) {
      super(
          ("%d test%s ran and passed in %s, but the coverage pass recorded no line of the %d"
                  + " class%s with mutants, so a score would measure nothing: every mutant would"
                  + " read as reached by no test. Most likely mutastic's coverage never reached"
                  + " the code the tests ran: an agent in the tests' JVM arguments that rewrites"
                  + " classes as they load, as another coverage tool's does (%s); a test"
                  + " framework or class loader that loads the code outside mutastic's (%s); or"
                  + " tests that reach none of this code (the build's test filters). The"
                  + " worker's output is in %s")
              .formatted(
                  tests,
                  tests == 1 ? "" : "s",
                  project.name(),
                  classes,
                  classes == 1 ? "" : "es",
                  agents(project.jvmArgs()),
                  framework(project),
                  log));
    }

    /// The agents mutastic left in the tests' JVM arguments: it leaves out the coverage agents it
    /// knows, but another may rewrite classes as they load too.
    private static String agents(List<String> jvmArgs) {
      var agents = jvmArgs.stream().filter(arg -> arg.startsWith("-javaagent:")).toList();
      return agents.isEmpty()
          ? "here the tests' JVM arguments load none"
          : "here: "
              + String.join(" ", agents)
              + "; take it out of the test task's JVM arguments for mutastic's run";
    }

    private static String framework(Project project) {
      return TestFrameworks.loadsTheApp(project.dependencies())
          ? "here: Quarkus, whose copies of the classes such an agent rewrites first"
          : "none known here";
    }
  }

  /// The tests failed before any mutant: nothing a mutant does can be judged against them.
  public static final class BaselineFailed extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    /// The most failing tests the message names, each with what it said; the log has the rest.
    private static final int NAMED = 5;

    /// Not serialised: nothing sends this exception anywhere, it's reported where it's caught.
    private final transient List<String> failing;

    /// @param baseline the coverage pass's failures: each test's exception and causes, and message
    /// @param services the JDK services the project provides that the worker can't install: each a
    ///   likely reason tests that pass in the build fail here
    BaselineFailed(
        List<String> failing, TestWorker.Baseline baseline, Path log, List<String> services) {
      this("red-baseline", said(failing, baseline, log, services), failing);
    }

    /// What went wrong, as the `run-failed` event names it.
    private final String code;

    private BaselineFailed(String code, String message, List<String> failing) {
      super(message);
      this.code = code;
      this.failing = List.copyOf(failing);
    }

    /// `red-baseline`; `tests-stopped` when a test ended their JVM; `tests-timed-out` when they ran
    /// past the coverage pass's limit.
    public String code() {
      return code;
    }

    /// The tests' JVM stopped, or ran past its limit, in the coverage pass: with no mutant in, so a
    /// test did it, the one running if the worker said which.
    static BaselineFailed stopped(TestWorker.Stopped stopped, Path log) {
      var running = stopped.running();
      var during = running.map(", while %s ran"::formatted).orElse("");
      if (stopped.exited()) {
        return new BaselineFailed(
            "tests-stopped",
            ("the tests' JVM stopped during the coverage pass, with no mutant in%s.\n"
                    + "A test that calls System.exit, or crashes the JVM, stops it. The end of its"
                    + " output says which:\n  tail -n 40 %s")
                .formatted(during, shown(log)),
            List.of());
      }
      return new BaselineFailed(
          "tests-timed-out",
          ("the tests didn't finish within %d minutes, with no mutant in%s.\nA test that waits"
                  + " for what never comes, or never stops, keeps them going. Their output is in"
                  + " %s.")
              .formatted(TestWorker.COVERAGE_LIMIT.toMinutes(), during, shown(log)),
          running.stream().toList());
    }

    /// The same, ending in the command that runs the first failing test alone in its build.
    public BaselineFailed endingIn(String command) {
      return new BaselineFailed(
          code,
          "%s To run %s alone:\n  %s"
              .formatted(getMessage(), failing.size() == 1 ? "it" : "the first", command),
          failing);
    }

    private static String said(
        List<String> failing, TestWorker.Baseline baseline, Path log, List<String> services) {
      var failures = baseline.failures();
      var messages = baseline.messages();
      var where = loadedElsewhere(failing, services);
      if (failing.size() == 1) {
        var test = failing.getFirst();
        var said = messages.getOrDefault(test, "");
        return "%s%s fails without any mutant, so mutastic can't judge one.%s%s\nIts output is in %s."
            .formatted(
                test,
                asAWhole(test, failures),
                said.isBlank() ? "" : "\nIt failed: " + said,
                where,
                shown(log));
      }
      var named =
          failing.stream()
              .limit(NAMED)
              .map(test -> "  " + test + asAWhole(test, failures) + saying(test, messages))
              .collect(Collectors.joining("\n"));
      var more = failing.size() > NAMED ? "\n  and %d more".formatted(failing.size() - NAMED) : "";
      return "%d tests fail without any mutant, so mutastic can't judge one:\n%s%s%s\nTheir output is in %s."
          .formatted(failing.size(), named, more, where, shown(log));
    }

    /// `log` from where the person is, when it's inside it.
    private static Path shown(Path log) {
      var here = Path.of("").toAbsolutePath().normalize();
      var absolute = log.toAbsolutePath().normalize();
      return absolute.startsWith(here) ? here.relativize(absolute) : absolute;
    }

    /// `: expected: <24> but was: <25>`, what the test's failure said, if it said anything.
    private static String saying(String test, Map<String, String> messages) {
      var message = messages.getOrDefault(test, "");
      return message.isBlank() ? "" : ": " + message;
    }

    /// For a class that failed as a whole, where: outside its tests, in what it runs around them.
    /// It ran again as a class and failed each time, or it would have been left out as flaky.
    private static String asAWhole(String test, Map<String, String> failures) {
      if (!TestIds.isClass(test)) {
        return "";
      }
      var with =
          java.util.Optional.ofNullable(failures.get(test))
              .map(causes -> causes.split(">", -1)[0])
              .map(name -> ", with " + name.substring(name.lastIndexOf('.') + 1))
              .orElse("");
      return (" (the class as a whole%s, each time it ran: its setup or teardown fails outside any"
              + " test, such as a `@BeforeAll`, `@AfterAll`, `@BeforeClass`, `@AfterClass`, a class"
              + " rule or a static initialiser)")
          .formatted(with);
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
      return ("\nIf they pass in the build, this is likely why: the project provides %s in"
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
  /// @param ghosts find and run ghosts (experimental), reported apart and never in the score
  /// @param killMatrix run every covering test of a caught mutant, not only up to the first catch
  /// @param shard the share of the mutants that runs: every one unless the run is split
  /// @param testsLeftOut whether the run leaves out tests the build runs (`--exclude-tests`): the
  ///   lines only they reach count as not covered, even if that's every line
  /// @param forceCoverage run the coverage pass even when no mutant is there to run
  ///   (`--force-coverage`), to check or time the tests; with none to run, otherwise, no test runs
  public record Options(
      List<String> only,
      boolean confirmCatches,
      int workers,
      boolean suggestions,
      boolean auditArid,
      java.util.Set<String> among,
      Policy policy,
      Optional<ChangedLines> changed,
      boolean ghosts,
      boolean killMatrix,
      Shard shard,
      boolean testsLeftOut,
      boolean forceCoverage) {
    /// Options that stop at a mutant's first catch.
    public Options(
        List<String> only,
        boolean confirmCatches,
        int workers,
        boolean suggestions,
        boolean auditArid,
        java.util.Set<String> among,
        Policy policy,
        Optional<ChangedLines> changed,
        boolean ghosts) {
      this(
          only,
          confirmCatches,
          workers,
          suggestions,
          auditArid,
          among,
          policy,
          changed,
          ghosts,
          false,
          Shard.WHOLE,
          false,
          false);
    }

    /// The same options, running every covering test of a caught mutant, to find each that catches
    /// it (`--kill-matrix`), not only the first.
    public Options withKillMatrix(boolean every) {
      return new Options(
          only,
          confirmCatches,
          workers,
          suggestions,
          auditArid,
          among,
          policy,
          changed,
          ghosts,
          every,
          shard,
          testsLeftOut,
          forceCoverage);
    }

    /// The same options, for one share of the mutants (`--shard`).
    public Options withShard(Shard share) {
      return new Options(
          only,
          confirmCatches,
          workers,
          suggestions,
          auditArid,
          among,
          policy,
          changed,
          ghosts,
          killMatrix,
          share,
          testsLeftOut,
          forceCoverage);
    }

    /// The same options, leaving out tests the build runs or not (`--exclude-tests`).
    public Options withTestsLeftOut(boolean leftOut) {
      return new Options(
          only,
          confirmCatches,
          workers,
          suggestions,
          auditArid,
          among,
          policy,
          changed,
          ghosts,
          killMatrix,
          shard,
          leftOut,
          forceCoverage);
    }

    /// The same options, running the coverage pass even with no mutant to run, or not
    /// (`--force-coverage`).
    public Options withForceCoverage(boolean force) {
      return new Options(
          only,
          confirmCatches,
          workers,
          suggestions,
          auditArid,
          among,
          policy,
          changed,
          ghosts,
          killMatrix,
          shard,
          testsLeftOut,
          force);
    }

    /// Options without ghosts.
    public Options(
        List<String> only,
        boolean confirmCatches,
        int workers,
        boolean suggestions,
        boolean auditArid,
        java.util.Set<String> among,
        Policy policy,
        Optional<ChangedLines> changed) {
      this(only, confirmCatches, workers, suggestions, auditArid, among, policy, changed, false);
    }

    /// The same options, for only the mutants with these ids.
    public Options among(java.util.Set<String> ids) {
      return new Options(
          only,
          confirmCatches,
          workers,
          suggestions,
          auditArid,
          ids,
          policy,
          changed,
          ghosts,
          killMatrix,
          shard,
          testsLeftOut,
          forceCoverage);
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
    // With no mutant to run, starting the workers and running every test would take minutes to
    // measure nothing.
    if (nothingToRun(plan, options)) {
      var skipped = skippedAll(plan.mutants(), listener);
      return unrun(project, plan, options, skipped, Duration.ofNanos(System.nanoTime() - start));
    }
    var workers = workersFor(project, options.workers(), plan.mutants().size());
    try {
      var ran = ran(project, plan, workers, listener);
      return ran.report(project, plan, options, Duration.ofNanos(System.nanoTime() - start));
    } finally {
      workers.forEach(TestWorker::close);
    }
  }

  /// Whether no mutant or ghost is there to run: each is skipped, and none is audited.
  private static boolean nothingToRun(Plan plan, Options options) {
    return !options.forceCoverage()
        && plan.ghosts().isEmpty()
        && classesToRun(plan.mutants(), options.auditArid()).isEmpty();
  }

  /// The report of a run with no mutant to run: the skipped mutants, with their reasons, and no
  /// test run, no worker started and no score.
  private static Report unrun(
      Project project, Plan plan, Options options, List<MutantResult> skipped, Duration took) {
    return new Report(
        project.name(),
        jdkOf(project.java()),
        skipped,
        0,
        List.of(),
        Duration.ZERO,
        options.workers(),
        0,
        took,
        List.of(),
        options.changed().map(changed -> changed.within(project.sources())),
        plan.stale(),
        List.of(),
        options.ghosts() ? Optional.of(List.of()) : Optional.empty(),
        Duration.ZERO);
  }

  /// Each mutant's result as skipped, told to `listener` as a run tells it: each mutant, then each
  /// source file once its last is told.
  private static List<MutantResult> skippedAll(List<Mutant> mutants, Listener listener) {
    var results = mutants.stream().map(MutantResult::skipped).toArray(MutantResult[]::new);
    var files = new SourceFiles(mutants);
    for (int at = 0; at < results.length; at++) {
      listener.mutantFinished(results[at]);
      files.finished(at, results, listener);
    }
    return List.of(results);
  }

  /// The feature version of the JDK `java` is in, from its `release` file, without starting it;
  /// this JVM's when that can't be read.
  static int jdkOf(Path java) {
    var bin = java.toAbsolutePath().getParent();
    var home = bin == null ? null : bin.getParent();
    return home == null ? Runtime.version().feature() : releaseOf(home.resolve("release"));
  }

  /// The feature version a JDK's `release` file names; this JVM's when it names none.
  private static int releaseOf(Path release) {
    try {
      for (String line : Files.readAllLines(release)) {
        if (line.startsWith("JAVA_VERSION=")) {
          var version = line.substring("JAVA_VERSION=".length()).replace("\"", "");
          return Runtime.Version.parse(version).feature();
        }
      }
    } catch (IOException | IllegalArgumentException e) {
      // no release file to read: this JVM's version stands in
    }
    return Runtime.version().feature();
  }

  /// The coverage pass, then every mutant, and the flaky tests both found, on `workers`.
  ///
  /// @param checking how long what follows the mutants took: the ghosts, and the catches checked
  private record Ran(
      CoveragePass pass,
      List<MutantResult> results,
      List<FlakyTest> flaky,
      List<Ghost> ghosts,
      Duration checking,
      List<TestWorker> workers) {
    /// The run's report, the whole of it having taken `took`.
    Report report(Project project, Plan plan, Options options, Duration took) {
      return new Report(
          project.name(),
          workers.getFirst().jdk(),
          results,
          plan.mutantRun().coverage().testCount(),
          flaky.stream().map(FlakyTest::test).toList(),
          pass.took(),
          Engine.ran(workers),
          workers.stream().mapToInt(TestWorker::started).sum(),
          took,
          inOrderFound(plan.mutantRun().audited(), plan.mutants()),
          options.changed().map(changed -> changed.within(project.sources())),
          plan.stale(),
          flaky,
          options.ghosts() ? Optional.of(ghosts) : Optional.empty(),
          checking);
    }
  }

  /// Each mutant, then each catch checked on the unmutated code ([FalseCatchGuard]), once the
  /// workers that ran them have gone with what their tests started (a server on a fixed port).
  private Ran ran(Project project, Plan plan, List<TestWorker> workers, Listener listener) {
    listener.mutantsFound(plan.mutants().size());
    var pass = coveragePass(project, plan, workers.getFirst(), listener);
    listener.coverageFinished(pass);
    var results = new SideBySide(plan.mutants(), plan.mutantRun(), workers, listener).runAll();
    long checkingStart = System.nanoTime();
    var ghosts = ghostsRun(plan, workers.getFirst(), listener);
    workers.forEach(TestWorker::close);
    listener.checking(FalseCatchGuard.STEP, 0, 0);
    var guarded = guard(plan, workers.getFirst()).check(results, listener);
    return new Ran(
        pass,
        guarded.results(),
        flakyBoth(pass, plan.mutantRun(), project, guarded),
        ghosts,
        Duration.ofNanos(System.nanoTime() - checkingStart),
        workers);
  }

  /// What progress calls running the ghosts, after the mutants.
  static final String GHOSTS_STEP = "running the ghosts";

  /// Each ghost, run once every mutant has its result, on `worker`.
  private static List<Ghost> ghostsRun(Plan plan, TestWorker worker, Listener listener) {
    var ghosts = plan.ghosts();
    var ran = new ArrayList<Ghost>();
    for (var found : ghosts) {
      listener.checking(GHOSTS_STEP, ran.size(), ghosts.size());
      ran.add(found.with(plan.mutantRun().run(found.mutant(), worker).result()));
    }
    return List.copyOf(ran);
  }

  /// The flaky tests: those that failed in the coverage pass and passed again, then those that
  /// caught a mutant and failed on their own with no mutant in, after the mutants.
  private static List<FlakyTest> flakyBoth(
      CoveragePass pass, MutantRun mutantRun, Project project, FalseCatchGuard.Guarded guarded) {
    var aboutThem =
        new FlakyEvidence(mutantRun.coverage(), mutantRun.models(), project)
            .aboutFailingAlone(guarded.failingAlone());
    return Stream.concat(pass.flakyEvidence().stream(), aboutThem.stream()).toList();
  }

  /// What checks each catch on the unmutated code, once the mutants have run, in a worker like
  /// `first` but new; a mutant whose catch was false runs again there.
  private FalseCatchGuard guard(Plan plan, TestWorker first) {
    var mutantRun = plan.mutantRun();
    return new FalseCatchGuard(
        mutantRun.coverage(),
        () -> first.another(named(log, "unmutated")),
        (mutant, worker) -> mutantRun.run(mutant, worker).result());
  }

  /// A run's mutants and what running them needs, before any test has run.
  ///
  /// @param instrumented each class with a mutant to run, instrumented for the coverage pass
  /// @param stale the project's ignores that no longer hold
  /// @param ghosts the ghosts to run after the mutants, with `--experimental=ghosts`
  /// @param whole whether the run is the module's every mutant, on every test the build runs: not
  ///   `--only`, `--since`, a share or `--exclude-tests`, where tests that reach none of the
  ///   classes in it can be the honest answer
  private record Plan(
      List<Mutant> mutants,
      MutantRun mutantRun,
      Map<String, byte[]> instrumented,
      List<Ignore> stale,
      List<Ghosts.Found> ghosts,
      boolean whole) {}

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
    var mutations =
        new Mutations(context, options.policy(), ClassFiles.supertypesFrom(classpath(project)));
    mutations.prepare(models.values());
    mutations.prepareFor(project);
    var mutants =
        mutantsIn(models, mutations, options.only(), project.name()).stream()
            .filter(mutant -> options.among().isEmpty() || options.among().contains(mutant.id()))
            .filter(inScope(models, project, options.changed()))
            .toList();
    var ignored = new Ignored(options.policy().ignores(), models, project);
    boolean complete =
        options.only().isEmpty() && options.among().isEmpty() && options.changed().isEmpty();
    var applied =
        complete
            ? ignored.applyToAll(
                mutants, () -> underEveryOperator(project, options, context, models))
            : ignored.apply(mutants);
    // A share is taken last, so the ignores are judged as in the whole run.
    var shared = applied.stream().filter(mutant -> options.shard().holds(mutant.id())).toList();
    return new Found(shared, mutations, ignored.stale());
  }

  /// Every mutant the project has, whichever operators this run chose.
  private static List<Mutant> underEveryOperator(
      Project project, Options options, ClassFile context, Map<String, ClassModel> models) {
    var policy = options.policy();
    var mutations =
        new Mutations(
            context,
            policy.withOperators(List.of("all"), policy.mutators()),
            ClassFiles.supertypesFrom(classpath(project)));
    mutations.prepare(models.values());
    return mutantsIn(models, mutations, List.of(), project.name());
  }

  private static Plan plan(Project project, Options options) {
    var context = contextFor(project);
    var models = classesIn(project.classes(), context);
    var found = found(project, options, context, models);
    var mutants = found.mutants();
    var mutations = found.mutations();
    var coverage = new Coverage(context);
    var ghosts =
        options.ghosts() ? ghostsIn(models, mutations, options.shard()) : List.<Ghosts.Found>of();
    var toRun = new ArrayList<>(mutants);
    ghosts.forEach(ghost -> toRun.add(ghost.mutant()));
    var instrumented = instrumentMutated(models, toRun, coverage, options.auditArid());
    var mutantRun =
        new MutantRun(
            models,
            mutations,
            coverage,
            options.confirmCatches(),
            options.suggestions(),
            options.auditArid(),
            java.util.Collections.synchronizedList(new ArrayList<>()),
            project,
            options.killMatrix());
    return new Plan(mutants, mutantRun, instrumented, found.stale(), ghosts, whole(options));
  }

  private static boolean whole(Options options) {
    return options.only().isEmpty()
        && options.among().isEmpty()
        && options.changed().isEmpty()
        && options.shard().whole()
        && !options.testsLeftOut();
  }

  /// The ghosts in the module's classes that would load, in this run's share: one that doesn't
  /// verify proves nothing.
  private static List<Ghosts.Found> ghostsIn(
      Map<String, ClassModel> models, Mutations mutations, Shard shard) {
    var byInternalName =
        models.values().stream()
            .collect(Collectors.toMap(model -> model.thisClass().asInternalName(), model -> model));
    var finder = new Ghosts(new MissingNotification(), byInternalName);
    return models.values().stream()
        .flatMap(model -> finder.find(model).stream())
        .filter(found -> shard.holds(found.mutant().id()))
        .filter(
            found -> mutations.unviable(model(models, found.mutant()), found.mutant()).isEmpty())
        .toList();
  }

  private static ClassModel model(Map<String, ClassModel> models, Mutant mutant) {
    return requireNonNull(models.get(mutant.binaryName()), mutant.binaryName());
  }

  /// Classes resolve from the project's own, its tests', the build's other modules' and its
  /// dependencies'.
  private static ClassFile contextFor(Project project) {
    return ClassFiles.resolvingFrom(classpath(project));
  }

  private static List<Path> classpath(Project project) {
    return Stream.of(
            project.classes(), project.testClasses(), project.alongside(), project.dependencies())
        .flatMap(List::stream)
        .toList();
  }

  /// Every mutant in the classes, or only those with the ids `only`, which must all be there.
  private static List<Mutant> mutantsIn(
      Map<String, ClassModel> models, Mutations mutations, List<String> only, String projectName) {
    var every = models.values().stream().flatMap(model -> mutations.find(model).stream()).toList();
    if (only.isEmpty()) {
      return every;
    }
    var found = every.stream().map(Mutant::id).collect(Collectors.toSet());
    only.stream()
        .filter(id -> !found.contains(id))
        .findFirst()
        .ifPresent(
            id -> {
              throw new UnknownMutant(id, projectName, Nearest.id(id, found));
            });
    return every.stream().filter(mutant -> only.contains(mutant.id())).toList();
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

  /// A worker for each mutant to run side by side, up to `wanted`; none has started yet. The
  /// workers that ran something: a run with fewer mutants to run than workers leaves some
  /// unstarted, and "4 workers (-1 replaced)" said so wrongly.
  private static int ran(List<TestWorker> workers) {
    return (int) Math.max(1, workers.stream().filter(worker -> worker.started() > 0).count());
  }

  private List<TestWorker> workersFor(Project project, int wanted, int mutantCount) {
    var workers = new ArrayList<TestWorker>();
    var toWarm = new ClassesToWarm();
    var resources = ResourceCopy.of(project, resourcesBeside(log));
    for (int number = 1; number <= Math.min(wanted, Math.max(1, mutantCount)); number++) {
      var numberedLog = number == 1 ? log : numbered(log, number);
      workers.add(new TestWorker(project, workerJar, numberedLog, toWarm, resources));
    }
    return workers;
  }

  /// Every test once, on `first`, with coverage; flaky tests are left out of what it recorded. A
  /// module with no test classes has nothing to run: every mutant is not covered, and no worker
  /// starts (one couldn't, with no JUnit on the classpath).
  private CoveragePass coveragePass(
      Project project, Plan plan, TestWorker first, Listener listener) {
    var testClasses =
        testClassesIn(project.testClasses()).stream().filter(project.tests()::runs).toList();
    if (testClasses.isEmpty()) {
      return new CoveragePass(0, 0, Duration.ZERO, plan.mutants().size(), List.of());
    }
    var coverage = plan.mutantRun().coverage();
    long coverageStart = System.nanoTime();
    var baseline = covered(first, plan, testClasses, listener);
    var reruns = new Reruns(coverage, first, Duration.ofNanos(System.nanoTime() - coverageStart));
    var flaky = baseline.passed() ? List.<String>of() : flakyOrFail(baseline, reruns, project);
    boolean recordedNothing = coverage.recordedNothing();
    // Before they're left out: what each flaky test reached is in what the pass recorded.
    var evidence =
        new FlakyEvidence(coverage, plan.mutantRun().models(), project)
            .about(flaky, baseline.failures());
    coverage.exclude(flaky);
    var coverageTook = Duration.ofNanos(System.nanoTime() - coverageStart);
    measured(project, plan, recordedNothing);
    return new CoveragePass(
        first.jdk(), coverage.testCount(), coverageTook, plan.mutants().size(), flaky, evidence);
  }

  /// The coverage pass's tests run on `first`: a test that stops its JVM, or keeps it past the
  /// pass's limit, fails the run as a test failing with no mutant in does.
  private TestWorker.Baseline covered(
      TestWorker first, Plan plan, List<String> testClasses, Listener listener) {
    try {
      return first.cover(plan.instrumented(), testClasses, plan.mutantRun().coverage(), listener);
    } catch (TestWorker.Stopped stopped) {
      throw BaselineFailed.stopped(stopped, log);
    }
  }

  /// Stops a run whose coverage pass measured nothing: no test ran, or tests ran and passed and the
  /// probes in the classes with mutants never did. Scored, that would be every mutant not covered:
  /// a 0% that reads as tests that don't reach the code, when it's mutastic that didn't see them. A
  /// run of only some mutants, or on only some tests, is let through, as there the tests may well
  /// reach none of the classes.
  private void measured(Project project, Plan plan, boolean recordedNothing) {
    int tests = plan.mutantRun().coverage().testCount();
    if (tests == 0) {
      throw new NoTestsRan(project);
    }
    if (recordedNothing && plan.whole()) {
      throw new NothingCovered(project, tests, plan.instrumented().size(), log);
    }
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

    /// What each mutant should take, in the order found.
    private final List<MutantCost> costs;

    SideBySide(
        List<Mutant> mutants, MutantRun mutantRun, List<TestWorker> workers, Listener listener) {
      this.mutants = mutants;
      this.mutantRun = mutantRun;
      this.workers = workers;
      this.listener = listener;
      this.results = new MutantResult[mutants.size()];
      this.files = new SourceFiles(mutants);
      this.idle = new java.util.concurrent.LinkedBlockingQueue<>(workers);
      this.costs = mutants.stream().map(mutantRun::cost).toList();
    }

    List<MutantResult> runAll() {
      listener.mutantsPlanned(
          workers.size(),
          costs.stream().map(MutantCost::expected).reduce(Duration.ZERO, Duration::plus));
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
      int number = workers.indexOf(worker);
      try {
        var result = started(at, number, worker);
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
        listener.workerIdle(number);
        idle.add(worker);
      }
    }

    /// Mutant `at` run on `worker`, worker `number`, once progress has heard it's started: unless
    /// it costs nothing, as one no test reaches, which is done at once.
    private MutantRun.Outcome started(int at, int number, TestWorker worker) {
      var cost = costs.get(at);
      if (cost.expected().isPositive()) {
        listener.mutantStarted(number, mutants.get(at), cost);
      }
      return mutantRun.run(mutants.get(at), worker);
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
        results[at] = started(at, 0, workers.getFirst()).result();
        listener.mutantFinished(results[at]);
        listener.workerIdle(0);
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
    return named(log, String.valueOf(number));
  }

  /// `resources-worker` beside `worker.log`: the project's resources, for its libraries.
  private static Path resourcesBeside(Path log) {
    var name = log.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return log.resolveSibling("resources-" + (dot < 0 ? name : name.substring(0, dot)));
  }

  /// `worker-unmutated.log` beside `worker.log`.
  private static Path named(Path log, String suffix) {
    var name = log.getFileName().toString();
    int dot = name.lastIndexOf('.');
    return log.resolveSibling(
        dot < 0
            ? name + "-" + suffix
            : name.substring(0, dot) + "-" + suffix + name.substring(dot));
  }

  /// The tests that failed in the coverage pass, if every one passes when run again on its own
  /// ([Reruns]): they're flaky, and the run goes on without them. A test that fails every try means
  /// the tests are red, and nothing a mutant does can be judged against them. A class whose setup
  /// or teardown failed runs again as a class, and if it passes it's flaky with every test in it.
  ///
  /// A flaky test left in would fail at random against mutants too, and its failures would count as
  /// catches it never made.
  private List<String> flakyOrFail(TestWorker.Baseline baseline, Reruns reruns, Project project) {
    var failing = Reruns.withoutTheirClasses(baseline.failing());
    var stillFailing = failing.stream().filter(test -> !reruns.passesAgain(test)).toList();
    if (!stillFailing.isEmpty()) {
      throw new BaselineFailed(stillFailing, baseline, log, SystemServices.providedBy(project));
    }
    return failing;
  }

  /// How long tests that took `normal` in the coverage pass may take in a later run.
  static Duration timeoutFor(Duration normal) {
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
              mutant.inlinedFrom().orElse(mutant.binaryName()),
              name ->
                  SourcePaths.of(
                      mutant,
                      Optional.ofNullable(models.get(mutant.binaryName())),
                      project.sources()));
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
  /// @param killMatrix whether a caught mutant runs its other covering tests too, to name each that
  ///   catches it
  private record MutantRun(
      Map<String, ClassModel> models,
      Mutations mutations,
      Coverage coverage,
      boolean confirmCatches,
      boolean suggestions,
      boolean auditArid,
      List<MutantResult> audited,
      Project project,
      boolean killMatrix) {
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
      return tested(mutant, worker, covering);
    }

    /// What `mutant` should take: the time its covering tests took in the coverage pass, every
    /// test's for a mutant of the whole class, as its timeout is reckoned. It's slow at three times
    /// that or half its timeout, whichever comes first. One that's skipped or no test reaches costs
    /// nothing.
    MutantCost cost(Mutant mutant) {
      var covering =
          mutant.skipped().isPresent() ? List.<String>of() : coverage.coveringTests(mutant);
      if (covering.isEmpty()) {
        return MutantCost.NONE;
      }
      var expected =
          mutant.operator() instanceof ClassMutator
              ? coverage.timeOfEveryTest()
              : coverage.timeOf(covering);
      var slow = expected.multipliedBy(3);
      var halfTimeout = timeoutFor(expected).dividedBy(2);
      return new MutantCost(
          expected, slow.compareTo(halfTimeout) < 0 ? slow : halfTimeout, covering.size());
    }

    /// The mutant run against the tests that reach it.
    private Outcome tested(Mutant mutant, TestWorker worker, List<String> covering) {
      var trial = new Trial(worker, mutant, verifiedMutation(mutant), coverage);
      var tested = testConfirmed(trial, covering);
      var inputs = coverage.inputs(mutant, covering);
      var result = resultOf(trial, covering, tested, inputs);
      if (killMatrix && tested.run().status() == Status.CAUGHT) {
        result = result.withCaughtByAll(everyCatcher(trial, covering, tested.run()));
      }
      if (suggestions && isOnAnInstruction(mutant) && tested.run().status() == Status.MISSED) {
        var receiver = receiverFor(mutant, result.coveredBy().getFirst());
        if (receiver.isPresent()) {
          result =
              result.withSuggestion(
                  suggest(
                      receiver.get(),
                      trial,
                      inputs,
                      new Tests(testClassOf(result.coveredBy().getFirst()), result.coveredBy())));
        }
      }
      return new Outcome(result, tested.run().portClash());
    }

    /// Every covering test that catches the mutant, from the one that did first: each run picks up
    /// after the last test that failed, until the rest pass or one doesn't finish in time. A test
    /// that failed but isn't among `covering` (a class failing as a whole) ends it there.
    private static List<String> everyCatcher(
        Trial trial, List<String> covering, TestWorker.Run first) {
      var catchers = new ArrayList<String>();
      var run = first;
      var rest = covering;
      while (run.status() == Status.CAUGHT && run.failedTest().isPresent()) {
        var catcher = run.failedTest().get();
        catchers.add(catcher);
        int at = rest.indexOf(catcher);
        if (at < 0 || at == rest.size() - 1) {
          break;
        }
        rest = rest.subList(at + 1, rest.size());
        run = trial.run(rest);
      }
      return catchers;
    }

    /// Whether a call to the mutant's method can show it: a change to a class as a whole has no one
    /// instruction, and may not be on a method at all. A ghost gets no test either: it's a question
    /// about what the code should do, and a test that catches it would only pin today's answer.
    private static boolean isOnAnInstruction(Mutant mutant) {
      return !(mutant.operator() instanceof ClassMutator)
          && !(mutant.operator() instanceof MissingNotification);
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
      if (run.status() != Status.CAUGHT) {
        return new Tested(run, Optional.empty());
      }
      var catcher = run.failedTest().orElseThrow();
      if (confirmCatches && trial.run(List.of(catcher)).status() == Status.MISSED) {
        return testedAfter(trial, covering, run, Optional.of(catcher));
      }
      return new Tested(run, Optional.empty());
    }

    /// Not a catch: the tests after the one that failed decide, as if it had passed.
    private Tested testedAfter(
        Trial trial, List<String> covering, TestWorker.Run run, Optional<String> unconfirmed) {
      int at = covering.indexOf(run.failedTest().orElseThrow());
      var rest = covering.subList(at + 1, covering.size());
      var after =
          rest.isEmpty()
              ? new Tested(new TestWorker.Run(Status.MISSED, 0), Optional.empty())
              : testConfirmed(trial, rest);
      var last = after.run();
      return new Tested(
          new TestWorker.Run(
              last.status(),
              last.failedTest(),
              last.failure(),
              last.origin(),
              run.testsRun() + last.testsRun(),
              last.portClash()),
          after.unconfirmed().or(() -> unconfirmed));
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
      return suggestions && isOnAnInstruction(mutant)
          ? result.withSuggestion(startingPoint(mutant, worker))
          : result;
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
      return suggest(
          receiver.get(), trial, Map.of(), new Tests(mutant.binaryName() + "Test", List.of()));
    }

    /// `com.example.PricingTest` for `com.example.PricingTest#bigOrders`.
    private static String testClassOf(String test) {
      int hash = test.indexOf('#');
      return hash < 0 ? test : test.substring(0, hash);
    }

    /// How the test class a suggestion would go into, the first covering test's as the result lists
    /// them, could write its call: `-` for a static method, else the constructor for the receiver.
    /// Empty if it can't.
    private Optional<String> receiverFor(Mutant mutant, String firstListed) {
      int hash = firstListed.indexOf('#');
      var testClass = hash < 0 ? firstListed : firstListed.substring(0, hash);
      synchronized (mutations) {
        return Access.receiver(models, mutant, testClass);
      }
    }

    /// Each candidate is the receiver's constructor arguments, if any, then the method's: first
    /// with the receiver made as a covering test makes it, when one does, so the call is one its
    /// reader knows and a field the receiver keeps has the value the tests use.
    private Optional<Suggestion> suggest(
        String receiver, Trial trial, Map<String, List<Long>> inputs, Tests tests) {
      var mutant = trial.mutant();
      var shape = CallShape.of(mutant, receiver);
      if (!shape.takesWholeNumbers()) {
        return Optional.empty();
      }
      var kotlin = isKotlin(mutant) ? kotlinCall(mutant) : Optional.<KotlinCall>empty();
      if (isKotlin(mutant) && kotlin.isEmpty()) {
        return Optional.empty();
      }
      var built = builtByATest(shape, tests.covering());
      var candidates = candidates(shape, candidateValues(mutant, inputs), built);
      var target = new Target(tests.into(), built);
      return trial
          .worker()
          .suggest(mutant, receiver, trial.bytes(), candidates)
          .map(found -> suggestion(shape, found, kotlin, target));
    }

    /// The test class a suggestion goes in, and the tests that reach the mutant, first listed
    /// first.
    private record Tests(String into, List<String> covering) {}

    /// How a suggestion's receiver may be made: the test class it goes in, and how a covering test
    /// makes it, if one does.
    private record Target(String testClass, Optional<Constructions.Built> built) {
      /// How the covering test makes the receiver, if a call made it with `arguments` too.
      Optional<Constructions.Built> builtWith(List<Long> arguments) {
        return built.filter(test -> test.arguments().equals(arguments));
      }
    }

    /// The calls to try: with the receiver made as `built` is first, its own values first among the
    /// rest, then every way from `values`.
    private static List<List<Long>> candidates(
        CallShape shape, java.util.SequencedSet<Long> values, Optional<Constructions.Built> built) {
      var candidates = new ArrayList<List<Long>>();
      built.ifPresent(
          made -> {
            var withItsValues = new java.util.LinkedHashSet<>(made.arguments());
            withItsValues.addAll(values);
            combine(
                List.copyOf(withItsValues),
                shape.arity(),
                new ArrayList<>(made.arguments()),
                candidates);
          });
      combine(List.copyOf(values), shape.arity(), new ArrayList<>(), candidates);
      return candidates;
    }

    /// How the first covering test that makes the receiver makes it, when it's one instance made of
    /// whole numbers written out.
    private Optional<Constructions.Built> builtByATest(CallShape shape, List<String> coveredBy) {
      if (shape.receiver().size() != 1 || shape.receiverArity() == 0 || coveredBy.isEmpty()) {
        return Optional.empty();
      }
      var made = shape.receiver().getFirst();
      return Constructions.in(
          project.testSources(),
          coveredBy,
          made.simpleName().replace('$', '.'),
          shape.receiverArity());
    }

    /// Whether kotlinc compiled the mutant's class, so a test of it is written in Kotlin.
    private boolean isKotlin(Mutant mutant) {
      synchronized (mutations) {
        return KotlinGenerated.isKotlin(model(mutant));
      }
    }

    private Optional<KotlinCall> kotlinCall(Mutant mutant) {
      synchronized (mutations) {
        return KotlinCall.of(model(mutant), mutant, project.sources());
      }
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
    private Suggestion suggestion(
        CallShape shape, String[] found, Optional<KotlinCall> kotlin, Target target) {
      var all = found[0].equals("-") ? List.<Long>of() : wholeNumbers(found[0]);
      int receiverCount = shape.receiverArity();
      var receiverArguments = all.subList(0, receiverCount);
      var arguments = all.subList(receiverCount, all.size());
      var names = namesOf(shape.mutant(), arguments.size());
      var builtBy = target.builtWith(receiverArguments).filter(test -> kotlin.isEmpty());
      var at =
          new NamedValues.Call(
              names, arguments, Optional.empty(), receiverArguments, target.testClass(), builtBy);
      var naming = kotlin.isPresent() ? Optional.<NamedValues.Naming>empty() : named(shape, at);
      return new Suggestion(
          kotlin
              .map(call -> shape.kotlin(call, receiverArguments, arguments))
              .orElseGet(() -> shape.call(receiverArguments, arguments, naming)),
          names,
          arguments,
          shape.instances(receiverArguments, this::parameterNames),
          found[1],
          found[2],
          naming.map(NamedValues.Naming::named),
          builtBy.map(Constructions.Built::test));
    }

    /// The mutant's method's parameter names, or `arg0`, `arg1`… if they aren't `count`.
    private List<String> namesOf(Mutant mutant, int count) {
      var names = parameterNames(mutant.className(), mutant.methodName(), mutant.descriptor());
      return names.size() == count ? names : placeholderNames(count);
    }

    /// The value the call is at the edge of, if the source names it. `call` has the receiver's
    /// arguments, all of them: its innermost constructor's are taken from them here.
    private Optional<NamedValues.Naming> named(CallShape shape, NamedValues.Call call) {
      var constructed = shape.innermostArguments(call.constructed());
      synchronized (mutations) {
        var constructor =
            shape
                .innermost()
                .flatMap(made -> method(made.className(), "<init>", made.descriptor()));
        return new NamedValues(models, project.sources())
            .of(
                shape.mutant(),
                new NamedValues.Call(
                    call.parameters(),
                    call.inputs(),
                    constructor,
                    constructed,
                    call.testClass(),
                    call.built()));
      }
    }

    /// The names a method's class file gives its parameters: see [ParameterNames].
    private List<String> parameterNames(String className, String name, String descriptor) {
      synchronized (mutations) {
        return method(className, name, descriptor).map(ParameterNames::of).orElse(List.of());
      }
    }

    private Optional<java.lang.classfile.MethodModel> method(
        String className, String name, String descriptor) {
      var model = models.get(className.replace('/', '.'));
      return model == null
          ? Optional.empty()
          : model.methods().stream()
              .filter(
                  method ->
                      method.methodName().equalsString(name)
                          && method.methodType().equalsString(descriptor))
              .findFirst();
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

        /// `Shipping(10)`, or `.Parcel(30)` for an inner class.
        String kotlin(List<Long> arguments) {
          var nested = className.substring(className.lastIndexOf('/') + 1).replace('$', '.');
          return (inner ? ".%s(%s)" : "%s(%s)")
              .formatted(inner ? simpleName() : nested, literals(parameters(), arguments));
        }

        String written(List<Long> arguments, Map<Integer, String> instead) {
          var nested = className.substring(className.lastIndexOf('/') + 1).replace('$', '.');
          return (inner ? ".new %s(%s)" : "new %s(%s)")
              .formatted(
                  inner ? simpleName() : nested,
                  String.join(", ", withInstead(literalList(parameters(), arguments), instead)));
        }

        String descriptor() {
          return constructor.descriptorString();
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

      /// The instances the call makes, with their arguments and the constructors' names for them.
      List<Suggestion.Instance> instances(List<Long> receiverArguments, ParameterNamer named) {
        var each = eachArguments(receiverArguments);
        return java.util.stream.IntStream.range(0, receiver.size())
            .mapToObj(
                index -> {
                  var made = receiver.get(index);
                  var names = named.of(made.className(), "<init>", made.descriptor());
                  var own =
                      made.inner() && !names.isEmpty() ? names.subList(1, names.size()) : names;
                  return new Suggestion.Instance(
                      made.simpleName(),
                      each.get(index),
                      own.size() == each.get(index).size() ? own : List.of());
                })
            .toList();
      }

      /// The instance the method is called on, if the call makes one.
      Optional<Made> innermost() {
        return receiver.isEmpty() ? Optional.empty() : Optional.of(receiver.getLast());
      }

      /// The arguments that instance is made with.
      List<Long> innermostArguments(List<Long> receiverArguments) {
        var each = eachArguments(receiverArguments);
        return each.isEmpty() ? List.of() : each.getLast();
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

      /// The call as Kotlin writes it: `discount(100)`, `Pricing(5).discount(100)`, `Shipping(10)
      /// .Parcel(30).heavy()`, or `1.doubled()`.
      String kotlin(KotlinCall call, List<Long> receiverArguments, List<Long> arguments) {
        var on = new StringBuilder();
        if (call.kind() == KotlinCall.Kind.ON_THE_CLASS) {
          var className = mutant.className();
          on.append(className.substring(className.lastIndexOf('/') + 1).replace('$', '.'));
        }
        var each = eachArguments(receiverArguments);
        for (int index = 0; index < receiver.size(); index++) {
          on.append(receiver.get(index).kotlin(each.get(index)));
        }
        return call.written(on.toString(), literalList(type.parameterList(), arguments));
      }

      /// `Pricing.discount(100)` for a static method, `new Pricing().discount(100)`, `new
      /// GoldenScorer.Score(1, 1).hitAt(1)` when the receiver needs arguments, or `new
      /// Shipping(10).new Parcel(30).heavy()` for an inner class.
      String call(
          List<Long> receiverArguments, List<Long> arguments, Optional<NamedValues.Naming> naming) {
        var on = new StringBuilder();
        if (receiver.isEmpty()) {
          var className = mutant.className();
          on.append(className.substring(className.lastIndexOf('/') + 1).replace('$', '.'));
        }
        var each = eachArguments(receiverArguments);
        for (int index = 0; index < receiver.size(); index++) {
          var instead =
              index == receiver.size() - 1
                  ? naming.map(NamedValues.Naming::constructed).orElse(Map.of())
                  : Map.<Integer, String>of();
          on.append(receiver.get(index).written(each.get(index), instead));
        }
        var written =
            withInstead(
                literalList(type.parameterList(), arguments),
                naming.map(NamedValues.Naming::arguments).orElse(Map.of()));
        return "%s.%s(%s)".formatted(on, mutant.methodName(), String.join(", ", written));
      }
    }

    /// Names a method's parameters: see [ParameterNames].
    @FunctionalInterface
    private interface ParameterNamer {
      List<String> of(String className, String name, String descriptor);
    }

    /// `written` with the values `instead` names written that way.
    private static List<String> withInstead(List<String> written, Map<Integer, String> instead) {
      var all = new ArrayList<>(written);
      instead.forEach(all::set);
      return all;
    }

    /// `1, 2L`: each value as Java source for its parameter (`long` and `Long` take an `L`).
    private static String literals(List<ClassDesc> types, List<Long> values) {
      return String.join(", ", literalList(types, values));
    }

    private static List<String> literalList(List<ClassDesc> types, List<Long> values) {
      var written = new ArrayList<String>();
      for (int index = 0; index < values.size(); index++) {
        var descriptor = types.get(index).descriptorString();
        written.add(
            values.get(index)
                + (descriptor.equals("J") || descriptor.equals("Ljava/lang/Long;") ? "L" : ""));
      }
      return written;
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
      if (mutant.operator() instanceof ClassMutator) {
        var timeout = timeoutFor(coverage.timeOfEveryTest());
        return worker.runFresh(mutant.binaryName(), bytes, tests, timeout);
      }
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
