package io.mutastic.cli;

import io.mutastic.api.Ignore;
import io.mutastic.api.Mutant;
import io.mutastic.engine.Engine;
import io.mutastic.report.SourceView;
import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Set;
import java.util.stream.Collectors;

/// `mutastic ignore`: records a decision not to test a mutant in `.mutastic/ignores.yaml`, with the
/// line it's on and the reason, or removes those that no longer hold. Runs no tests.
final class IgnoreCommand {
  private IgnoreCommand() {}

  /// Where the project is, and its sources, to say where a mutant is.
  record Where(Path directory, SourceView sources) {}

  static int run(Main.Ignoring how, Where where, Engine.Mutants found, PrintStream out) {
    return how.prune()
        ? prune(where.directory(), found, out)
        : ignore(found.all().getFirst(), how.reason(), where, out);
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
    var entry = entry(mutant, reason, where.sources());
    var entries = new ArrayList<Ignores.Entry>();
    Ignores.read(where.directory()).stream()
        .filter(existing -> !existing.ignore().id().equals(mutant.id()))
        .forEach(entries::add);
    entries.add(entry);
    Ignores.write(where.directory(), entries);
    out.printf(
        "mutastic: %s is ignored: %s%n  recorded in %s: commit it, as a decision to review%n",
        mutant.id(), reason, Ignores.FILE);
    return Main.PASSED;
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

  private static int prune(Path directory, Engine.Mutants found, PrintStream out) {
    Set<String> stale = found.stale().stream().map(Ignore::id).collect(Collectors.toSet());
    var entries = Ignores.read(directory);
    var kept = entries.stream().filter(entry -> !stale.contains(entry.ignore().id())).toList();
    if (kept.size() == entries.size()) {
      out.println("mutastic: every ignore still holds: nothing to remove");
      return Main.PASSED;
    }
    Ignores.write(directory, kept);
    out.printf(
        "mutastic: removed %d ignore%s that no longer hold: %s%n",
        entries.size() - kept.size(),
        entries.size() - kept.size() == 1 ? "" : "s",
        String.join(", ", stale.stream().sorted().toList()));
    return Main.PASSED;
  }
}
