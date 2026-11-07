package io.mutastic.cli;

import java.util.Map;
import java.util.Optional;

/// What to say when a build failed inside Codex's sandbox.
///
/// Codex runs an agent's commands under macOS's sandbox with `CODEX_SANDBOX` set. There a build
/// can't write its caches in the home directory (`~/.gradle`, `~/.m2`) or reach the network, and
/// each refusal reads "Operation not permitted", so the build fails with a stack trace that never
/// says why. The hint needs both pieces of evidence: the variable, and that refusal in what the
/// build said. A build that failed for its own reasons in the sandbox gets no hint.
final class CodexSandbox {
  private static final String VARIABLE = "CODEX_SANDBOX";
  private static final String REFUSED = "Operation not permitted";

  private CodexSandbox() {}

  /// The hint for a command that failed with `failure`, or empty when the sandbox isn't why.
  static Optional<String> hint(RuntimeException failure, Map<String, String> env) {
    var said = failure.getMessage();
    if (!env.containsKey(VARIABLE) || said == null || !said.contains(REFUSED)) {
      return Optional.empty();
    }
    return Optional.of(
        "mutastic: this ran in Codex's sandbox, which refused the build: there a build can't"
            + " write its caches in your home directory or use the network. Run mutastic outside"
            + " the sandbox: ask for that, or allow it with"
            + " prefix_rule(pattern = [\"mutastic\"], decision = \"allow\")"
            + " in ~/.codex/rules/default.rules");
  }
}
