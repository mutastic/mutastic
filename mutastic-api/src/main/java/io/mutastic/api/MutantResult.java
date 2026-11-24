package io.mutastic.api;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/// One mutant's outcome.
///
/// @param coveredBy the tests that reach the mutant's line, as they're listed: the mutant's own
///   test class's (`PricingTest`'s, and its nested classes', for `Pricing`) first, as the tests
///   most about its code, then the rest, each by name. Not the order they ran in, fastest first,
///   which is measured afresh each run and moves with the machine's load: a card is compared across
///   runs, kept in the docs and read by agents, so what it lists mustn't. The first is where a
///   suggested test goes.
/// @param caughtBy the test that failed, if one did
/// @param failure what it failed with: the exception's class, or `exit: 2` when the code it ran
///   ended the JVM with that status
/// @param failureOrigin where that came from: `assertion` (a check failed), `test` (the test's own
///   code threw), `code` (the code under test threw), `unknown` (no frame of the stack trace is in
///   the tests or the code: a framework or the JDK threw) or `exit` (the code it ran ended the JVM)
/// @param testsRun how many covering tests ran before the result was known
/// @param unconfirmed a test whose catch didn't repeat when run again, if catches were confirmed
/// @param inputs the whole numbers the covering tests passed to the mutant's method, by parameter
///   name: what the tests tried, so what they didn't is plain
/// @param suggestion a call whose result the mutant changes, for a missed mutant, if one was found
/// @param falseCatchBy a test that failed against the mutant, then failed on the unmutated code too
///   when run again after the mutants, so its catch wasn't the mutant's: the mutant ran again
///   without it, and this result is what the other covering tests made of it
/// @param caughtByAll with `--kill-matrix`, every covering test that catches it, in the order they
///   ran, the first being `caughtBy`; empty otherwise
/// @param caughtCase the case of `caughtBy` that failed, when it runs a table of cases: evidence of
///   this run, as its index moves when a case is added before it
/// @param caughtInput the input the property test `caughtBy` failed on, on one line as its
///   framework prints it (`Arg 0: 18`), when a property caught it and its framework names one:
///   evidence of this run, as another seed draws others
@Internal
public record MutantResult(
    Mutant mutant,
    Status status,
    List<String> coveredBy,
    Optional<String> caughtBy,
    Optional<String> failure,
    Optional<String> failureOrigin,
    int testsRun,
    Optional<String> unconfirmed,
    java.util.Map<String, List<Long>> inputs,
    Optional<Suggestion> suggestion,
    Duration took,
    Optional<String> falseCatchBy,
    List<String> caughtByAll,
    Optional<TestCase> caughtCase,
    Optional<String> caughtInput) {

  public MutantResult {
    coveredBy = listed(mutant, coveredBy);
    inputs = java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(inputs));
    caughtByAll = List.copyOf(caughtByAll);
  }

  /// A result that names no input: no property caught it, or its framework named none.
  public MutantResult(
      Mutant mutant,
      Status status,
      List<String> coveredBy,
      Optional<String> caughtBy,
      Optional<String> failure,
      Optional<String> failureOrigin,
      int testsRun,
      Optional<String> unconfirmed,
      java.util.Map<String, List<Long>> inputs,
      Optional<Suggestion> suggestion,
      Duration took,
      Optional<String> falseCatchBy,
      List<String> caughtByAll,
      Optional<TestCase> caughtCase) {
    this(
        mutant,
        status,
        coveredBy,
        caughtBy,
        failure,
        failureOrigin,
        testsRun,
        unconfirmed,
        inputs,
        suggestion,
        took,
        falseCatchBy,
        caughtByAll,
        caughtCase,
        Optional.empty());
  }

  /// A result that names no case: its catcher, if it has one, runs no table of them.
  public MutantResult(
      Mutant mutant,
      Status status,
      List<String> coveredBy,
      Optional<String> caughtBy,
      Optional<String> failure,
      Optional<String> failureOrigin,
      int testsRun,
      Optional<String> unconfirmed,
      java.util.Map<String, List<Long>> inputs,
      Optional<Suggestion> suggestion,
      Duration took,
      Optional<String> falseCatchBy,
      List<String> caughtByAll) {
    this(
        mutant,
        status,
        coveredBy,
        caughtBy,
        failure,
        failureOrigin,
        testsRun,
        unconfirmed,
        inputs,
        suggestion,
        took,
        falseCatchBy,
        caughtByAll,
        Optional.empty());
  }

  /// A result without every catcher: the run stopped at the first.
  public MutantResult(
      Mutant mutant,
      Status status,
      List<String> coveredBy,
      Optional<String> caughtBy,
      Optional<String> failure,
      Optional<String> failureOrigin,
      int testsRun,
      Optional<String> unconfirmed,
      java.util.Map<String, List<Long>> inputs,
      Optional<Suggestion> suggestion,
      Duration took,
      Optional<String> falseCatchBy) {
    this(
        mutant,
        status,
        coveredBy,
        caughtBy,
        failure,
        failureOrigin,
        testsRun,
        unconfirmed,
        inputs,
        suggestion,
        took,
        falseCatchBy,
        List.of());
  }

  /// A result whose catch, if it has one, stands.
  public MutantResult(
      Mutant mutant,
      Status status,
      List<String> coveredBy,
      Optional<String> caughtBy,
      Optional<String> failure,
      Optional<String> failureOrigin,
      int testsRun,
      Optional<String> unconfirmed,
      java.util.Map<String, List<Long>> inputs,
      Optional<Suggestion> suggestion,
      Duration took) {
    this(
        mutant,
        status,
        coveredBy,
        caughtBy,
        failure,
        failureOrigin,
        testsRun,
        unconfirmed,
        inputs,
        suggestion,
        took,
        Optional.empty());
  }

  public static MutantResult notCovered(Mutant mutant) {
    return new MutantResult(
        mutant,
        Status.NOT_COVERED,
        List.of(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        0,
        Optional.empty(),
        java.util.Map.of(),
        Optional.empty(),
        Duration.ZERO);
  }

  public static MutantResult skipped(Mutant mutant) {
    return new MutantResult(
        mutant,
        Status.SKIPPED,
        List.of(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        0,
        Optional.empty(),
        java.util.Map.of(),
        Optional.empty(),
        Duration.ZERO);
  }

  private static List<String> listed(Mutant mutant, List<String> tests) {
    var ownTestClass = mutant.binaryName() + "Test";
    return tests.stream()
        .sorted(
            java.util.Comparator.comparing(
                    (String test) ->
                        !test.startsWith(ownTestClass + "#")
                            && !test.startsWith(ownTestClass + "$"))
                .thenComparing(java.util.Comparator.naturalOrder()))
        .toList();
  }

  /// The same result, reached by `tests` too: another run's, over the same mutant.
  public MutantResult alsoCoveredBy(List<String> tests) {
    var all = new java.util.LinkedHashSet<>(coveredBy);
    all.addAll(tests);
    return new MutantResult(
        mutant,
        status,
        List.copyOf(all),
        caughtBy,
        failure,
        failureOrigin,
        testsRun,
        unconfirmed,
        inputs,
        suggestion,
        took,
        falseCatchBy,
        caughtByAll,
        caughtCase,
        caughtInput);
  }

  /// The same result with a suggested test.
  public MutantResult withSuggestion(Optional<Suggestion> found) {
    return new MutantResult(
        mutant,
        status,
        coveredBy,
        caughtBy,
        failure,
        failureOrigin,
        testsRun,
        unconfirmed,
        inputs,
        found,
        took,
        falseCatchBy,
        caughtByAll,
        caughtCase,
        caughtInput);
  }

  /// The same result, its catch by `test` found false: the mutant's result without that test.
  public MutantResult withFalseCatchBy(String test) {
    return new MutantResult(
        mutant,
        status,
        coveredBy,
        caughtBy,
        failure,
        failureOrigin,
        testsRun,
        unconfirmed,
        inputs,
        suggestion,
        took,
        Optional.of(test),
        caughtByAll,
        caughtCase,
        caughtInput);
  }

  /// The same result, with every covering test that catches it (`--kill-matrix`).
  public MutantResult withCaughtByAll(List<String> tests) {
    return new MutantResult(
        mutant,
        status,
        coveredBy,
        caughtBy,
        failure,
        failureOrigin,
        testsRun,
        unconfirmed,
        inputs,
        suggestion,
        took,
        falseCatchBy,
        tests,
        caughtCase,
        caughtInput);
  }

  /// The same result, naming the case of its catcher that failed.
  public MutantResult withCaughtCase(Optional<TestCase> failed) {
    return new MutantResult(
        mutant,
        status,
        coveredBy,
        caughtBy,
        failure,
        failureOrigin,
        testsRun,
        unconfirmed,
        inputs,
        suggestion,
        took,
        falseCatchBy,
        caughtByAll,
        failed,
        caughtInput);
  }

  /// The same result, naming the input its catcher, a property test, failed on.
  public MutantResult withCaughtInput(Optional<String> input) {
    return new MutantResult(
        mutant,
        status,
        coveredBy,
        caughtBy,
        failure,
        failureOrigin,
        testsRun,
        unconfirmed,
        inputs,
        suggestion,
        took,
        falseCatchBy,
        caughtByAll,
        caughtCase,
        input);
  }
}
