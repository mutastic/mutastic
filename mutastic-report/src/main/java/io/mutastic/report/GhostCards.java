package io.mutastic.report;

import io.mutastic.api.Ghost;
import io.mutastic.api.Report;
import io.mutastic.api.Status;
import java.util.List;

/// What a run says of its ghosts (`--experimental=ghosts`), apart from its mutants and never in its
/// score. Each says what's missing, why mutastic thinks it belongs there, and what running the
/// tests with it in found: every claim is the analysis's count or the ghost's own run. A ghost is a
/// question about what the code should do, so each ends with both answers.
final class GhostCards {
  private final SourceView sources;

  GhostCards(SourceView sources) {
    this.sources = sources;
  }

  static boolean any(Report report) {
    return !found(report).isEmpty();
  }

  /// Whether the run looked for ghosts, whether or not it found any.
  static boolean lookedFor(Report report) {
    return report.ghosts().isPresent();
  }

  /// The ghosts the run found: none if it didn't look.
  static List<Ghost> found(Report report) {
    return report.ghosts().orElse(List.of());
  }

  /// `3 (experimental, not in the score): 2 passed unseen, 1 appeared`, or that there were none.
  static String summary(Report report) {
    var ghosts = found(report);
    if (ghosts.isEmpty()) {
      return "none found (experimental, not in the score)";
    }
    long unseen = ghosts.stream().filter(Ghost::passedUnseen).count();
    long appeared = ghosts.stream().filter(Ghost::appeared).count();
    long notReached = ghosts.stream().filter(GhostCards::notReached).count();
    var counts = new java.util.ArrayList<String>();
    counts.add(unseen + " passed unseen");
    counts.add(appeared + " appeared");
    if (notReached > 0) {
      counts.add(notReached + " not reached by a test");
    }
    return "%d (experimental, not in the score): %s"
        .formatted(ghosts.size(), String.join(", ", counts));
  }

  /// `appeared`, `passedUnseen` or `notCovered`, as JSON has it.
  static String status(Ghost ghost) {
    if (ghost.appeared()) {
      return "appeared";
    }
    return ghost.passedUnseen() ? "passedUnseen" : "notCovered";
  }

  private static boolean notReached(Ghost ghost) {
    return ghost.result().status() == Status.NOT_COVERED;
  }

  /// Every ghost, those no test noticed first.
  String render(Report report) {
    var out = new StringBuilder("Ghosts (experimental, not in the score)\n");
    for (var ghost : ordered(found(report))) {
      out.append('\n');
      card(ghost).forEach(line -> out.append(line).append('\n'));
    }
    return out.toString();
  }

  private static List<Ghost> ordered(List<Ghost> ghosts) {
    return java.util.stream.Stream.of(
            ghosts.stream().filter(Ghost::passedUnseen),
            ghosts.stream().filter(GhostCards::notReached),
            ghosts.stream().filter(Ghost::appeared))
        .flatMap(stream -> stream)
        .toList();
  }

  /// One ghost's lines: where, why, what its run found, and what to do.
  List<String> card(Ghost ghost) {
    var mutant = ghost.result().mutant();
    var where = "%s:%d".formatted(sources.file(mutant), mutant.line());
    var method = simpleClass(mutant.className()) + "." + mutant.methodName() + "()";
    var call = "firePropertyChange(\"%s\", old, new)".formatted(ghost.property());
    if (ghost.appeared()) {
      return List.of(
          "  %s  %s stays quiet, and a test expects it to: %s fails with %s added after its write to `%s`"
              .formatted(
                  where,
                  method,
                  AgentText.shortName(ghost.result().caughtBy().orElse("a test")),
                  call,
                  ghost.field()));
    }
    var lines = new java.util.ArrayList<String>();
    lines.add(
        "  %s  %s writes `%s` without telling listeners".formatted(where, method, ghost.field()));
    lines.add("    " + why(ghost));
    lines.add("    " + found(ghost, call));
    lines.add(
        "    If listeners should hear of it, fire the change there. If it's quiet on purpose, a test"
            + " that listens and expects no event says so.");
    return lines;
  }

  /// Why the call looks missing: the rule's count, and the peers it was copied from.
  private static String why(Ghost ghost) {
    var peers = String.join(", ", ghost.peers().stream().map(GhostCards::peer).toList());
    return switch (ghost.rule()) {
      case SAME_FIELD ->
          "%d of %d writes to `%s` fire a property change: %s"
              .formatted(ghost.peers().size(), ghost.writes(), ghost.field(), peers);
      case SIBLING_FIELDS ->
          "The class's %d other properties fire a property change on every write: %s"
              .formatted(ghost.writes() - 1, peers);
    };
  }

  /// `deposit (:49) fires "balance"`, or for a write announced through a method of the class's own,
  /// `setStatus (:83) through a method that fires`.
  private static String peer(Ghost.Peer peer) {
    return peer.property().isEmpty()
        ? "%s (:%d) through a method that fires".formatted(peer.method(), peer.line())
        : "%s (:%d) fires \"%s\"".formatted(peer.method(), peer.line(), peer.property());
  }

  /// What running the tests with the call in found.
  private static String found(Ghost ghost, String call) {
    if (notReached(ghost)) {
      return "No test runs this write, so nothing could notice %s added after it.".formatted(call);
    }
    int tests = ghost.result().coveredBy().size();
    return "With %s added after it, the %s that %s it still %s."
        .formatted(
            call,
            tests == 1 ? "1 test" : tests + " tests",
            tests == 1 ? "runs" : "run",
            tests == 1 ? "passes" : "pass");
  }

  private static String simpleClass(String internalName) {
    return internalName.substring(internalName.lastIndexOf('/') + 1);
  }
}
