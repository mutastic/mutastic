package io.mutastic.engine;

import io.mutastic.api.Ignore;
import io.mutastic.api.Mutant;
import io.mutastic.api.Project;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.ClassModel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/// The project's ignores applied to its mutants: each still on the line it was ignored on is
/// skipped with the project's reason; one whose line has changed no longer holds, and its mutant
/// runs. An ignore comes after an annotation on the code and before a rule or a built-in filter,
/// and never hides a mutant that is generated or equivalent, which already doesn't run.
final class Ignored {
  private final Map<String, Ignore> byId = new LinkedHashMap<>();
  private final Map<String, ClassModel> models;
  private final List<Path> sources;
  private final Map<Path, List<String>> files = new HashMap<>();
  private final List<Ignore> stale = new ArrayList<>();

  Ignored(List<Ignore> ignores, Map<String, ClassModel> models, Project project) {
    ignores.forEach(ignore -> byId.put(ignore.id(), ignore));
    this.models = models;
    this.sources = project.sources();
  }

  /// The mutants, the ignored ones skipped. With `complete`, every mutant of the project is here,
  /// so an ignore matching none (its code is gone) no longer holds either.
  List<Mutant> apply(List<Mutant> mutants, boolean complete) {
    var matched = new java.util.HashSet<String>();
    var applied = new ArrayList<Mutant>();
    for (Mutant mutant : mutants) {
      var ignore = byId.get(mutant.id());
      if (ignore == null) {
        applied.add(mutant);
        continue;
      }
      matched.add(ignore.id());
      applied.add(applied(mutant, ignore));
    }
    if (complete) {
      byId.values().stream().filter(ignore -> !matched.contains(ignore.id())).forEach(stale::add);
    }
    return applied;
  }

  /// The ignores that no longer hold: their line changed, or their code is gone.
  List<Ignore> stale() {
    return List.copyOf(stale);
  }

  private Mutant applied(Mutant mutant, Ignore ignore) {
    var line = line(mutant);
    if (line.isEmpty() || !ignore.holdsFor(line.get())) {
      stale.add(ignore);
      return mutant;
    }
    var skipped = mutant.skipped().orElse("");
    boolean overridable =
        skipped.isEmpty() || skipped.startsWith("arid:") || skipped.contains("(rule `");
    return overridable ? mutant.skippedFor("user: " + ignore.reason() + " (ignored)") : mutant;
  }

  /// The mutant's source line as it is now.
  private Optional<String> line(Mutant mutant) {
    return Optional.ofNullable(models.get(mutant.binaryName()))
        .flatMap(model -> SourcePaths.of(model, sources))
        .map(this::lines)
        .filter(lines -> mutant.line() >= 1 && mutant.line() <= lines.size())
        .map(lines -> lines.get(mutant.line() - 1));
  }

  private List<String> lines(Path file) {
    return files.computeIfAbsent(
        file,
        path -> {
          try {
            return Files.readAllLines(path);
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        });
  }
}
