package io.mutastic.api;

/// What happened to a mutant. Reports say "caught" and "missed"; the JSONL stream uses the
/// mutation-testing report schema's names, so it folds into tools that read that schema.
public enum Status {
  /// A test failed with the mutant in.
  CAUGHT("Killed"),
  /// Every test that reaches the mutant still passes: a gap in the tests.
  MISSED("Survived"),
  /// The tests didn't finish in time, usually a loop that no longer ends. Counts as caught.
  TIMED_OUT("Timeout"),
  /// No test reaches the mutant's line.
  NOT_COVERED("NoCoverage"),
  /// Never run, for the reason on the mutant: left out of the score.
  SKIPPED("Ignored");

  private final String schemaName;

  Status(String schemaName) {
    this.schemaName = schemaName;
  }

  public String schemaName() {
    return schemaName;
  }

  /// Whether the tests noticed the change.
  public boolean detected() {
    return this == CAUGHT || this == TIMED_OUT;
  }
}
