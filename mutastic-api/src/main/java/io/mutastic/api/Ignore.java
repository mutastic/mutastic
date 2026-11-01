package io.mutastic.api;

/// A mutant the project has decided not to test, by id, from `.mutastic/ignores.yaml`: written by
/// `mutastic ignore`, committed, and reviewed like code.
///
/// @param code the source line the mutant was on, trimmed, when it was ignored: once the line
///   changes, the decision no longer holds and the mutant runs again
public record Ignore(String id, String code, String reason) {
  /// Whether the decision still holds for the line as it is now.
  public boolean holdsFor(String line) {
    return code.equals(line.strip());
  }
}
