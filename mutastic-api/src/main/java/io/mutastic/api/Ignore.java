package io.mutastic.api;

/// A mutant the project has decided not to test, by id, from `.mutastic/ignores.yaml`: written by
/// `mutastic ignore`, committed, and reviewed like code.
///
/// @param code the source line the mutant was on, trimmed, when it was ignored: once the line
///   changes, the decision no longer holds and the mutant runs again
/// @param kind what sort of decision it is, one of [#KINDS], or empty when it doesn't say
public record Ignore(String id, String code, String reason, String kind) {
  /// The sorts of decision: the change can't be told apart, its case can't happen in practice, it
  /// only feeds logs or metrics, the code is on its way out, or the risk is accepted. Counted
  /// apart, so what the ignores hide can be reviewed by sort.
  public static final java.util.List<String> KINDS =
      java.util.List.of("equivalent", "unreachable", "telemetry", "deprecated", "accepted-risk");

  /// A decision that doesn't say its sort.
  public Ignore(String id, String code, String reason) {
    this(id, code, reason, "");
  }

  /// `user: unreachable: totals are rounded (ignored)`: the skip reason its mutant gets.
  public String skipReason() {
    return "user: " + (kind.isEmpty() ? "" : kind + ": ") + reason + " (ignored)";
  }

  /// Whether the decision still holds for the line as it is now.
  public boolean holdsFor(String line) {
    return code.equals(line.strip());
  }
}
