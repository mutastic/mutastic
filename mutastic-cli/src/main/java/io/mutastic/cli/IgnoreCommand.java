package io.mutastic.cli;

import io.mutastic.api.Ignore;
import io.mutastic.api.Mutant;
import io.mutastic.engine.Engine;
import io.mutastic.report.MutantCommand;
import io.mutastic.report.SourceView;
import io.mutastic.report.Untrusted;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/// `mutastic ignore`: records a decision not to test a mutant in `.mutastic/ignores.yaml`, with the
/// line it's on and the reason; proposes one, in `.mutastic/proposals.yaml`, for a person to
/// accept; accepts a proposal; removes one; or removes those that no longer hold. Runs no tests,
/// and says how to undo what it did.
final class IgnoreCommand {
  private IgnoreCommand() {}

  /// Where the project is, its sources (to say where a mutant is), and the ignores file as the
  /// person would type it.
  record Where(Path directory, SourceView sources, String shownFile) {
    /// `mutastic ignore …` for this project, as typed from where mutastic ran, or the Maven goal.
    String command(List<String> words) {
      var all = new ArrayList<>(List.of("ignore"));
      all.addAll(words);
      return sources.invocation().command(all);
    }

    /// The proposals file, as the person would type it: beside the ignores.
    String shownProposals() {
      return Path.of(shownFile).resolveSibling(Ignores.PROPOSALS.getFileName()).toString();
    }
  }

  static int run(Main.Ignoring how, Where where, Engine.Mutants found, PrintStream out) {
    if (how.prune()) {
      return prune(where, found, out);
    }
    return how.propose()
        ? propose(found.all().getFirst(), how, where, out)
        : ignore(found.all().getFirst(), how, where, out);
  }

  /// A proposal, waiting for a person: its mutant still counts as missed until it's accepted.
  private static int propose(Mutant mutant, Main.Ignoring how, Where where, PrintStream out) {
    var proposals = withoutProposal(mutant.id(), where);
    proposals.add(entry(mutant, how, where.sources()));
    Ignores.propose(where.directory(), proposals);
    out.printf(
        "mutastic: %s is proposed for ignoring: %s\n  recorded in %s: it counts as missed until a"
            + " person accepts it\n  to accept: %s\n  to withdraw: %s\n",
        mutant.id(),
        Untrusted.value(how.reason()),
        where.shownProposals(),
        where.command(List.of("--accept", mutant.id())),
        where.command(List.of("--remove", mutant.id())));
    return Main.PASSED;
  }

  /// A proposal accepted: it moves into the ignores, with its reason and sort as proposed.
  static int accept(String id, Where where, PrintStream out) {
    var proposal =
        Ignores.proposed(where.directory()).stream()
            .filter(entry -> entry.ignore().id().equals(id))
            .findFirst();
    if (proposal.isEmpty()) {
      return notProposed(id, where, out);
    }
    var entries = without(id, where);
    entries.add(proposal.get());
    Ignores.write(where.directory(), entries);
    Ignores.propose(where.directory(), withoutProposal(id, where));
    out.printf(
        "mutastic: %s is ignored, as proposed: %s\n  recorded in %s: commit it, as a decision to"
            + " review\n  to undo: %s\n",
        id,
        Untrusted.value(proposal.get().ignore().reason()),
        where.shownFile(),
        where.command(List.of("--remove", id)));
    return Main.PASSED;
  }

  /// `id` isn't proposed: with the proposal a slip away from it, if there's one.
  private static int notProposed(String id, Where where, PrintStream out) {
    out.printf(
        "mutastic: %s isn't proposed in %s: nothing to accept%s\n",
        id,
        where.shownProposals(),
        close(id, Ignores.proposed(where.directory()), "--accept", where));
    return Main.FAILED;
  }

  /// The one id in `entries` a slip away from `id`, said with its command: never acted on.
  private static String close(String id, List<Ignores.Entry> entries, String action, Where where) {
    var ids = entries.stream().map(entry -> entry.ignore().id()).toList();
    return io.mutastic.api.Nearest.id(id, ids)
        .map(
            close ->
                ". There's one called %s:\n  %s"
                    .formatted(close, where.command(List.of(action, close))))
        .orElse("");
  }

  private static List<Ignores.Entry> withoutProposal(String id, Where where) {
    return new ArrayList<>(
        Ignores.proposed(where.directory()).stream()
            .filter(existing -> !existing.ignore().id().equals(id))
            .toList());
  }

  private static int ignore(Mutant mutant, Main.Ignoring how, Where where, PrintStream out) {
    var reason = how.reason();
    var already = mutant.skipped().filter(skip -> !skip.endsWith("(ignored)"));
    if (already
        .filter(skip -> skip.startsWith("generated:") || skip.startsWith("equivalent:"))
        .isPresent()) {
      out.printf(
          "mutastic: %s doesn't run anyway (%s): nothing to ignore\n",
          mutant.id(), Untrusted.value(already.get()));
      return Main.PASSED;
    }
    var entries = without(mutant.id(), where);
    entries.add(entry(mutant, how, where.sources()));
    Ignores.write(where.directory(), entries);
    Ignores.propose(where.directory(), withoutProposal(mutant.id(), where)); // decided now
    out.printf(
        """
        mutastic: %s is ignored: %s
          recorded in %s: commit it, as a decision to review
          to undo: %s
        """,
        mutant.id(),
        Untrusted.value(reason),
        where.shownFile(),
        where.command(List.of("--remove", mutant.id())));
    return Main.PASSED;
  }

  static int remove(String id, Where where, PrintStream out) {
    var removed =
        Ignores.read(where.directory()).stream()
            .filter(entry -> entry.ignore().id().equals(id))
            .findFirst();
    if (removed.isEmpty() && withdraw(id, where, out)) {
      return Main.PASSED;
    }
    if (removed.isEmpty()) {
      out.printf(
          "mutastic: %s isn't ignored in %s: nothing to remove%s\n",
          id, where.shownFile(), close(id, Ignores.read(where.directory()), "--remove", where));
      return Main.PASSED;
    }
    Ignores.write(where.directory(), without(id, where));
    out.printf(
        "mutastic: %s is no longer ignored: it runs again\n  removed from %s\n  to undo: %s\n",
        id, where.shownFile(), where.command(undo(removed.get().ignore())));
    return Main.PASSED;
  }

  /// A proposal taken back, if `id` was proposed.
  private static boolean withdraw(String id, Where where, PrintStream out) {
    var proposal =
        Ignores.proposed(where.directory()).stream()
            .filter(entry -> entry.ignore().id().equals(id))
            .findFirst();
    if (proposal.isEmpty()) {
      return false;
    }
    Ignores.propose(where.directory(), withoutProposal(id, where));
    var again = new ArrayList<>(undo(proposal.get().ignore()));
    again.add(1, "--propose");
    out.printf(
        "mutastic: %s is no longer proposed\n  removed from %s\n  to undo: %s\n",
        id, where.shownProposals(), where.command(again));
    return true;
  }

  /// The words that ignore it again as it was: its reason, and its sort if it had one.
  private static List<String> undo(Ignore ignore) {
    var words = new ArrayList<>(List.of(ignore.id(), "--reason=" + ignore.reason()));
    if (!ignore.kind().isEmpty()) {
      words.add("--as=" + ignore.kind());
    }
    return words;
  }

  /// The entries without `id`'s, in order.
  private static List<Ignores.Entry> without(String id, Where where) {
    return new ArrayList<>(
        Ignores.read(where.directory()).stream()
            .filter(existing -> !existing.ignore().id().equals(id))
            .toList());
  }

  /// The decision, with where the mutant is and what it changes, for whoever reviews it.
  private static Ignores.Entry entry(Mutant mutant, Main.Ignoring how, SourceView sources) {
    var line = sources.line(mutant).orElse("").strip();
    var at =
        "%s#%s, line %d"
            .formatted(mutant.className().replace('/', '.'), mutant.methodName(), mutant.line());
    return new Ignores.Entry(
        new Ignore(mutant.id(), line, how.reason(), how.kind()),
        at,
        sources.advice(mutant).change());
  }

  private static int prune(Where where, Engine.Mutants found, PrintStream out) {
    Set<String> stale = found.stale().stream().map(Ignore::id).collect(Collectors.toSet());
    var entries = Ignores.read(where.directory());
    var kept = entries.stream().filter(entry -> !stale.contains(entry.ignore().id())).toList();
    if (kept.size() == entries.size()) {
      out.print("mutastic: every ignore still holds: nothing to remove\n");
      return Main.PASSED;
    }
    Ignores.write(where.directory(), kept);
    int removed = entries.size() - kept.size();
    out.printf(
        """
        mutastic: removed %d ignore%s that no longer hold: %s
          from %s
          to undo: git checkout -- %s
        """,
        removed,
        removed == 1 ? "" : "s",
        Untrusted.values(stale.stream().sorted().toList()),
        where.shownFile(),
        MutantCommand.shell(where.shownFile()));
    return Main.PASSED;
  }
}
