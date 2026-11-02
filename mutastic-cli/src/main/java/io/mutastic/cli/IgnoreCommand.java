package io.mutastic.cli;

import io.mutastic.api.Ignore;
import io.mutastic.api.Mutant;
import io.mutastic.engine.Engine;
import io.mutastic.report.MutantCommand;
import io.mutastic.report.SourceView;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/// `mutastic ignore`: records a decision not to test a mutant in `.mutastic/ignores.yaml`, with the
/// line it's on and the reason; removes one; or removes those that no longer hold. Runs no tests,
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
  }

  static int run(Main.Ignoring how, Where where, Engine.Mutants found, PrintStream out) {
    if (how.prune()) {
      return prune(where, found, out);
    }
    return ignore(found.all().getFirst(), how.reason(), where, out);
  }

  private static int ignore(Mutant mutant, String reason, Where where, PrintStream out) {
    var already = mutant.skipped().filter(skip -> !skip.endsWith("(ignored)"));
    if (already
        .filter(skip -> skip.startsWith("generated:") || skip.startsWith("equivalent:"))
        .isPresent()) {
      out.printf(
          "mutastic: %s doesn't run anyway (%s): nothing to ignore%n", mutant.id(), already.get());
      return Main.PASSED;
    }
    var entries = without(mutant.id(), where);
    entries.add(entry(mutant, reason, where.sources()));
    Ignores.write(where.directory(), entries);
    out.printf(
        "mutastic: %s is ignored: %s%n  recorded in %s: commit it, as a decision to review%n"
            + "  to undo: %s%n",
        mutant.id(), reason, where.shownFile(), where.command(List.of("--remove", mutant.id())));
    return Main.PASSED;
  }

  static int remove(String id, Where where, PrintStream out) {
    var removed =
        Ignores.read(where.directory()).stream()
            .filter(entry -> entry.ignore().id().equals(id))
            .findFirst();
    if (removed.isEmpty()) {
      out.printf("mutastic: %s isn't ignored in %s: nothing to remove%n", id, where.shownFile());
      return Main.PASSED;
    }
    Ignores.write(where.directory(), without(id, where));
    out.printf(
        "mutastic: %s is no longer ignored: it runs again%n  removed from %s%n  to undo: %s%n",
        id,
        where.shownFile(),
        where.command(List.of(id, "--reason=" + removed.get().ignore().reason())));
    return Main.PASSED;
  }

  /// The entries without `id`'s, in order.
  private static List<Ignores.Entry> without(String id, Where where) {
    return new ArrayList<>(
        Ignores.read(where.directory()).stream()
            .filter(existing -> !existing.ignore().id().equals(id))
            .toList());
  }

  /// The decision, with where the mutant is and what it changes, for whoever reviews it.
  private static Ignores.Entry entry(Mutant mutant, String reason, SourceView sources) {
    var line = sources.line(mutant).orElse("").strip();
    var at =
        "%s#%s, line %d"
            .formatted(mutant.className().replace('/', '.'), mutant.methodName(), mutant.line());
    return new Ignores.Entry(
        new Ignore(mutant.id(), line, reason), at, sources.advice(mutant).change());
  }

  private static int prune(Where where, Engine.Mutants found, PrintStream out) {
    Set<String> stale = found.stale().stream().map(Ignore::id).collect(Collectors.toSet());
    var entries = Ignores.read(where.directory());
    var kept = entries.stream().filter(entry -> !stale.contains(entry.ignore().id())).toList();
    if (kept.size() == entries.size()) {
      out.println("mutastic: every ignore still holds: nothing to remove");
      return Main.PASSED;
    }
    Ignores.write(where.directory(), kept);
    int removed = entries.size() - kept.size();
    out.printf(
        "mutastic: removed %d ignore%s that no longer hold: %s%n  from %s%n"
            + "  to undo: git checkout -- %s%n",
        removed,
        removed == 1 ? "" : "s",
        String.join(", ", stale.stream().sorted().toList()),
        where.shownFile(),
        MutantCommand.shell(where.shownFile()));
    return Main.PASSED;
  }
}
