package io.mutastic.cli;

import io.mutastic.api.Mutant;
import io.mutastic.api.MutantResult;
import io.mutastic.report.FailOnNew;
import io.mutastic.report.SourceView;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.SequencedSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/// `.mutastic/baseline.yaml`: the mutants a project missed when its baseline was taken, by id,
/// written by `mutastic baseline` and committed. A miss that isn't in it is new: `gate` holds only
/// new misses, and `next` offers them first. It shrinks with `baseline --prune` as tests catch its
/// mutants, and is never rewritten to take in new misses, or it would hide them.
///
/// ```yaml
/// baseline:
///   - id: m855c8e3c
///     at: com.example.pricing.Pricing#discount, line 17
///     change: '`>=` becomes `>`'
/// ```
///
/// Ids alone decide: a mutant's id is its class, method, position and change, and stays the same
/// while code around it moves (`StableIdsTest`). `at` and `change` are for whoever reads a diff of
/// it. YAML, as the ignores and proposals are, one entry an id, in order of class and method and
/// then id, so a line moving doesn't reorder it.
final class Baseline {
  static final Path FILE = Path.of(".mutastic", "baseline.yaml");

  private static final List<String> HEADER =
      List.of(
          "# The mutants this project missed when its baseline was taken, written by `mutastic"
              + " baseline`.",
          "# Commit it. A miss that isn't here is new: the gate holds only new misses. As tests",
          "# catch these, `mutastic baseline --prune` drops them; it never takes in new misses.",
          "baseline:");

  private static final Pattern ID = Pattern.compile("^\\s*+- id: (?<id>m[0-9a-f]{8})\\s*+$");

  /// One mutant, with where it is and what it changes, for the reader.
  record Entry(String id, String at, String change) {}

  private Baseline() {}

  /// The ids in the project's baseline; empty when it has none.
  static SequencedSet<String> ids(Path directory) {
    var file = directory.resolve(FILE);
    return Files.isRegularFile(file) ? idsIn(read(file)) : new LinkedHashSet<>();
  }

  /// The ids in a baseline's text.
  static SequencedSet<String> idsIn(String text) {
    var ids = new LinkedHashSet<String>();
    text.lines()
        .map(ID::matcher)
        .filter(Matcher::matches)
        .forEach(found -> ids.add(found.group("id")));
    return ids;
  }

  /// The baseline as it was at a commit: what a gated task started from.
  static SequencedSet<String> idsAt(Path directory, String commit) {
    return new Git(directory).at(commit, FILE).map(Baseline::idsIn).orElseGet(LinkedHashSet::new);
  }

  static boolean exists(Path directory) {
    return Files.isRegularFile(directory.resolve(FILE));
  }

  /// A mutant no test catches, missed or reached by none: what a baseline holds, and what `run
  /// --fail-on-new` counts as a new miss when the baseline doesn't hold it.
  static boolean uncaught(MutantResult result) {
    return FailOnNew.uncaught(result);
  }

  /// An entry for each mutant no test catches, in the file's order.
  static List<Entry> entries(List<MutantResult> results, SourceView sources) {
    return results.stream()
        .filter(Baseline::uncaught)
        .map(MutantResult::mutant)
        .sorted(
            Comparator.comparing(Mutant::className)
                .thenComparing(Mutant::methodName)
                .thenComparing(Mutant::id))
        .map(mutant -> new Entry(mutant.id(), at(mutant), sources.advice(mutant).change()))
        .toList();
  }

  private static String at(Mutant mutant) {
    return "%s#%s, line %d"
        .formatted(mutant.className().replace('/', '.'), mutant.methodName(), mutant.line());
  }

  /// Writes the entries, replacing the file.
  static void write(Path directory, List<Entry> entries) {
    var lines = new ArrayList<>(HEADER);
    for (var entry : entries) {
      lines.add("  - id: " + entry.id());
      lines.add("    at: " + quoted(entry.at()));
      lines.add("    change: " + quoted(entry.change()));
    }
    writeLines(directory.resolve(FILE), lines);
  }

  /// Drops the entries whose ids are in `caught`, each whole, and keeps the rest as they were: how
  /// many it dropped.
  static int prune(Path directory, Set<String> caught) {
    var file = directory.resolve(FILE);
    var kept = new ArrayList<String>();
    int dropped = 0;
    boolean dropping = false;
    for (var line : read(file).lines().toList()) {
      var id = ID.matcher(line);
      if (id.matches()) {
        dropping = caught.contains(id.group("id"));
        dropped += dropping ? 1 : 0;
      } else if (!line.startsWith("    ")) {
        dropping = false;
      }
      if (!dropping) {
        kept.add(line);
      }
    }
    writeLines(file, kept);
    return dropped;
  }

  /// In single quotes, as YAML has them: a quote inside is doubled.
  private static String quoted(String value) {
    return "'" + value.replace("'", "''") + "'";
  }

  private static String read(Path file) {
    try {
      return Files.readString(file);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  private static void writeLines(Path file, List<String> lines) {
    try {
      Files.createDirectories(file.getParent());
      Files.write(file, lines);
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
