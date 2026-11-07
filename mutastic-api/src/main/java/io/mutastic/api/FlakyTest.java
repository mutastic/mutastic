package io.mutastic.api;

import java.util.List;

/// What a flaky test's failure and reach say about why it's flaky: the difference between a test
/// that's its own doing (an unseeded `Random`, the clock), which can be made to give the same
/// answer every time, and one that reaches outside the code (the network, a database), where
/// changing the test or the code to get past it would hide what's wrong.
///
/// @param test the test, as `com.example.QuoteClientTest#aPriceComesFromTheFeed`
/// @param failure the exception it failed with and its causes, outermost first, by class name;
///   empty when the worker didn't say
/// @param reaches calls the code the test ran makes that give a different answer from one run to
///   the next: `new Random()`, `Instant.now`, `System.nanoTime`
/// @param reachesInTest the same, in the test's own class: its set-up, a stand-in it starts. A test
///   whose own code varies makes its own flakiness, even when it fails on the network: a stand-in
///   feed with a random delay times out by the test's doing, not the network's.
/// @param failsAlone it passed among the others, caught mutants, and failed on its own with no
///   mutant in when run again after them: it counts on something another test or the run sets up or
///   leaves behind, so its catches say nothing about a mutant, and the run doesn't pass
public record FlakyTest(
    String test,
    List<String> failure,
    List<String> reaches,
    List<String> reachesInTest,
    boolean failsAlone) {
  /// The exceptions that come from outside the code, by package: the network, a database, a channel
  /// or a file.
  private static final List<String> OUTSIDE =
      List.of(
          "java.net.",
          "javax.net.",
          "java.sql.",
          "javax.sql.",
          "java.nio.channels.",
          "java.nio.file.");

  public FlakyTest {
    failure = List.copyOf(failure);
    reaches = List.copyOf(reaches);
    reachesInTest = List.copyOf(reachesInTest);
  }

  public FlakyTest(
      String test, List<String> failure, List<String> reaches, List<String> reachesInTest) {
    this(test, failure, reaches, reachesInTest, false);
  }

  /// The same test, known to fail on its own.
  public FlakyTest failingAlone() {
    return new FlakyTest(test, failure, reaches, reachesInTest, true);
  }

  /// Whether it failed with something from outside the code: an exception, or a cause of one, from
  /// the network, a database, a channel or a file, or a timeout waiting on one.
  public boolean failedOutside() {
    return failure.stream()
        .anyMatch(
            name ->
                OUTSIDE.stream().anyMatch(name::startsWith)
                    || name.equals("java.util.concurrent.TimeoutException"));
  }

  /// The exception it failed with, by its simple name, if the worker said.
  public java.util.Optional<String> failedWith() {
    return failure.stream().findFirst().map(name -> name.substring(name.lastIndexOf('.') + 1));
  }

  /// The innermost cause from outside the code, by simple name: `HttpTimeoutException`.
  public java.util.Optional<String> outsideCause() {
    return failure.reversed().stream()
        .filter(
            name ->
                OUTSIDE.stream().anyMatch(name::startsWith)
                    || name.equals("java.util.concurrent.TimeoutException"))
        .findFirst()
        .map(name -> name.substring(name.lastIndexOf('.') + 1));
  }
}
