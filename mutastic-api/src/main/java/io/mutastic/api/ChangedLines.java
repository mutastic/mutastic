package io.mutastic.api;

import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

/// The source lines a change touched, by file: what a run scoped to a change mutates. Lines only a
/// deletion touched aren't here, since there's no code left on them to mutate.
///
/// @param since what the change is measured from, as given: `main`, `HEAD`
/// @param lines each changed source file, absolute, and its changed lines, from 1
public record ChangedLines(String since, Map<Path, Set<Integer>> lines) {
  public ChangedLines {
    lines = Map.copyOf(lines);
  }

  /// Whether `line` of `file` (absolute) changed.
  public boolean covers(Path file, int line) {
    return lines.getOrDefault(file.toAbsolutePath().normalize(), Set.of()).contains(line);
  }

  /// Only the files under `roots`: one module's sources, of a change across a repository.
  public ChangedLines within(java.util.List<Path> roots) {
    var absolute = roots.stream().map(root -> root.toAbsolutePath().normalize()).toList();
    var kept = new java.util.HashMap<Path, Set<Integer>>();
    lines.forEach(
        (file, changed) -> {
          if (absolute.stream().anyMatch(file::startsWith)) {
            kept.put(file, changed);
          }
        });
    return new ChangedLines(since, kept);
  }

  public int files() {
    return lines.size();
  }

  public int lineCount() {
    return lines.values().stream().mapToInt(Set::size).sum();
  }
}
