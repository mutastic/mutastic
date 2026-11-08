package io.mutastic.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.jspecify.annotations.Nullable;

/// A finished run: every mutant's result, in the order they were found.
///
/// @param jdk the feature version the tests ran on
/// @param flaky tests that failed in the coverage pass and passed when run again, and tests that
///   caught mutants and then failed on their own with no mutant in: left out, so their catches
///   don't count
/// @param coverage how long the coverage pass took, of `took`
/// @param workers how many workers ran side by side
/// @param started how many worker JVMs started: one per worker, plus one after each kill
/// @param audited in an audit of the arid filters, each arid mutant's result when run anyway; the
///   mutant itself is in `results` as skipped, as in any run
/// @param scope the change the run was scoped to, when it was: only mutants on its lines ran
/// @param staleIgnores the ignores whose line has changed since: they no longer hold, and their
///   mutants ran
/// @param flakyEvidence for each of the `flaky` tests the run found out about, what it failed with
///   and what it reaches that changes from run to run
@Internal
public record Report(
    String project,
    int jdk,
    List<MutantResult> results,
    int tests,
    List<String> flaky,
    Duration coverage,
    int workers,
    int started,
    Duration took,
    List<MutantResult> audited,
    Optional<ChangedLines> scope,
    List<Ignore> staleIgnores,
    List<FlakyTest> flakyEvidence) {

  public Report {
    staleIgnores = List.copyOf(staleIgnores);
    flakyEvidence = List.copyOf(flakyEvidence);
    results = List.copyOf(results);
    flaky = List.copyOf(flaky);
    audited = List.copyOf(audited);
  }

  /// A run with nothing known of why its flaky tests are flaky.
  public Report(
      String project,
      int jdk,
      List<MutantResult> results,
      int tests,
      List<String> flaky,
      Duration coverage,
      int workers,
      int started,
      Duration took,
      List<MutantResult> audited,
      Optional<ChangedLines> scope,
      List<Ignore> staleIgnores) {
    this(
        project,
        jdk,
        results,
        tests,
        flaky,
        coverage,
        workers,
        started,
        took,
        audited,
        scope,
        staleIgnores,
        List.of());
  }

  /// What's known of why the flaky test `test` is flaky, if anything is.
  public Optional<FlakyTest> whyFlaky(String test) {
    return flakyEvidence.stream().filter(evidence -> evidence.test().equals(test)).findFirst();
  }

  /// A run without an audit.
  public Report(
      String project,
      int jdk,
      List<MutantResult> results,
      int tests,
      List<String> flaky,
      Duration coverage,
      int workers,
      int started,
      Duration took) {
    this(
        project,
        jdk,
        results,
        tests,
        flaky,
        coverage,
        workers,
        started,
        took,
        List.of(),
        Optional.empty(),
        List.of());
  }

  /// This run's results with `other`'s, a run of other tests (another module's) over the same
  /// mutants: a mutant is caught if either caught it, missed rather than not covered if either
  /// reached it, and covered by the tests of both.
  public Report merge(Report other) {
    var theirs = new java.util.HashMap<String, MutantResult>();
    other.results().forEach(result -> theirs.put(result.mutant().id(), result));
    var merged =
        results.stream().map(mine -> combined(mine, theirs.get(mine.mutant().id()))).toList();
    var allFlaky = new java.util.ArrayList<>(flaky);
    allFlaky.addAll(other.flaky());
    var allEvidence = new java.util.ArrayList<>(flakyEvidence);
    allEvidence.addAll(other.flakyEvidence());
    return new Report(
        project,
        jdk,
        merged,
        tests + other.tests(),
        allFlaky,
        coverage.plus(other.coverage()),
        Math.max(workers, other.workers()),
        started + other.started(),
        took.plus(other.took()),
        audited,
        scope,
        staleIgnores,
        allEvidence);
  }

  private static MutantResult combined(MutantResult mine, @Nullable MutantResult theirs) {
    if (theirs == null || mine.status() == Status.SKIPPED || mine.status().detected()) {
      return mine;
    }
    boolean theirsTells =
        theirs.status().detected()
            || (theirs.status() == Status.MISSED && mine.status() == Status.NOT_COVERED);
    return theirsTells
        ? theirs.alsoCoveredBy(mine.coveredBy())
        : mine.alsoCoveredBy(theirs.coveredBy());
  }

  /// Workers started again after one was killed for taking too long: never fewer than none.
  public int replaced() {
    return Math.max(0, started - workers);
  }

  public long count(Status status) {
    return results.stream().filter(result -> result.status() == status).count();
  }

  /// The mutants that ran or could have: all but the skipped ones.
  public long scored() {
    return results.size() - count(Status.SKIPPED);
  }

  /// Detected mutants as a share of the scored ones, from 0 to 100; none when nothing was scored,
  /// which is no evidence either way, not a perfect score.
  public OptionalDouble score() {
    return scored() == 0
        ? OptionalDouble.empty()
        : OptionalDouble.of(
            100.0
                * results.stream().filter(result -> result.status().detected()).count()
                / scored());
  }

  /// Detected mutants as a share of those some test reached: how well the tests check the code they
  /// run, apart from the code they don't. None when no test reached any; the threshold is still on
  /// [#score].
  public OptionalDouble coveredScore() {
    long detected = results.stream().filter(result -> result.status().detected()).count();
    long reached = detected + count(Status.MISSED);
    return reached == 0 ? OptionalDouble.empty() : OptionalDouble.of(100.0 * detected / reached);
  }

  /// At or above `threshold`, or nothing to score: with no mutants there is nothing to fail. Never
  /// with a test that caught mutants and fails on its own with no mutant in: the tests aren't
  /// sound, and a score made with them isn't trusted to pass.
  public boolean passes(int threshold) {
    return failingAlone().isEmpty() && score().orElse(100) >= threshold;
  }

  /// The flaky tests that caught mutants, then failed on their own with no mutant in, run again
  /// after the mutants: their catches weren't the mutants', and while there are any the run doesn't
  /// pass.
  public List<FlakyTest> failingAlone() {
    return flakyEvidence.stream().filter(FlakyTest::failsAlone).toList();
  }
}
