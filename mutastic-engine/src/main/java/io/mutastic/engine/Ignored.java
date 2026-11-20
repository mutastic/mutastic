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
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

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

  /// The twins of each mutant one is the same as, by its id: an ignore of a twin holds for it.
  private final Map<String, List<Mutant>> twins = new HashMap<>();

  Ignored(List<Ignore> ignores, Map<String, ClassModel> models, Project project) {
    this(ignores, models, project, List.of());
  }

  /// The ignores, for mutants whose twins are among `every`.
  Ignored(
      List<Ignore> ignores, Map<String, ClassModel> models, Project project, List<Mutant> every) {
    ignores.forEach(ignore -> byId.put(ignore.id(), ignore));
    this.models = models;
    this.sources = project.sources();
    every.forEach(
        mutant ->
            mutant
                .sameAs()
                .ifPresent(
                    partner ->
                        twins.computeIfAbsent(partner, ignored -> new ArrayList<>()).add(mutant)));
  }

  /// The mutants of a run that sees only part of the project, the ignored ones skipped.
  List<Mutant> apply(List<Mutant> mutants) {
    var applied = new ArrayList<Mutant>();
    for (Mutant mutant : mutants) {
      var ignore = byId.get(mutant.id());
      applied.add(ignore == null ? byTwin(mutant) : applied(mutant, ignore));
    }
    return applied;
  }

  /// The mutant, skipped for an ignore of a twin of it that still holds: the twin's id was kept,
  /// maybe from before mutastic knew the two are the same, and its decision is this one's.
  private Mutant byTwin(Mutant mutant) {
    for (Mutant twin : twins.getOrDefault(mutant.id(), List.of())) {
      var ignore = byId.get(twin.id());
      if (ignore != null && holds(ignore, twin)) {
        return skipped(mutant, ignore);
      }
    }
    return mutant;
  }

  /// The mutants of a run of the whole project, the ignored ones skipped. An ignore matching none
  /// may only be on an operator this run doesn't use, a reviewed decision that still holds, so
  /// `underEveryOperator` is asked (only then, as it costs) whether its code is gone or its line
  /// has changed.
  List<Mutant> applyToAll(List<Mutant> mutants, Supplier<List<Mutant>> underEveryOperator) {
    var here = mutants.stream().map(Mutant::id).collect(Collectors.toSet());
    var elsewhere = byId.values().stream().filter(ignore -> !here.contains(ignore.id())).toList();
    if (!elsewhere.isEmpty()) {
      var found = new HashMap<String, Mutant>();
      underEveryOperator.get().forEach(mutant -> found.put(mutant.id(), mutant));
      elsewhere.stream()
          .filter(ignore -> !holds(ignore, found.get(ignore.id())))
          .forEach(stale::add);
    }
    return apply(mutants);
  }

  private boolean holds(Ignore ignore, @Nullable Mutant mutant) {
    return mutant != null && line(mutant).filter(ignore::holdsFor).isPresent();
  }

  /// The ignores that no longer hold: their line changed, or their code is gone.
  List<Ignore> stale() {
    return List.copyOf(stale);
  }

  private Mutant applied(Mutant mutant, Ignore ignore) {
    if (!holds(ignore, mutant)) {
      stale.add(ignore);
      return mutant;
    }
    return skipped(mutant, ignore);
  }

  /// The mutant skipped for the ignore, unless a skip of its own already says more.
  private static Mutant skipped(Mutant mutant, Ignore ignore) {
    var skipped = mutant.skipped().orElse("");
    boolean overridable =
        skipped.isEmpty() || skipped.startsWith("arid:") || skipped.contains("(rule `");
    return overridable ? mutant.skippedFor(ignore.skipReason()) : mutant;
  }

  /// The mutant's source line as it is now.
  private Optional<String> line(Mutant mutant) {
    return SourcePaths.of(mutant, Optional.ofNullable(models.get(mutant.binaryName())), sources)
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
