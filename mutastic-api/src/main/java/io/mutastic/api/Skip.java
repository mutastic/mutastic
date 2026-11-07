package io.mutastic.api;

import java.util.Locale;

/// Why a mutant doesn't run. Skipped mutants stay in the report with their reason and out of the
/// score.
///
/// @param reason in words, after the kind: `trivial getter of name`
public record Skip(Kind kind, String reason) {
  /// What the skip claims: `GENERATED`, `EQUIVALENT` and `UNVIABLE` are facts about the bytecode
  /// (an unviable change wouldn't load, or would never finish); `ARID` is a judgement that the
  /// mutant isn't worth a test; `USER` is the project's own decision.
  public enum Kind {
    GENERATED,
    EQUIVALENT,
    UNVIABLE,
    ARID,
    USER
  }

  /// `arid: trivial getter of name`, as reports show it: the kind first, so it can be counted.
  @Override
  public String toString() {
    return kind.name().toLowerCase(Locale.ROOT) + ": " + reason;
  }
}
