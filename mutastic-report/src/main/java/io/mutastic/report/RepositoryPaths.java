package io.mutastic.report;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/// Paths from the root of the git repository a project is in, as code scanning and GitHub's
/// annotations resolve them, where the project may be a folder down.
final class RepositoryPaths {
  private RepositoryPaths() {}

  /// The git repository `project` is in: the nearest directory, from the project up, with a `.git`
  /// in it.
  static Optional<Path> repository(Path project) {
    for (var at = project.toAbsolutePath().normalize(); at != null; at = at.getParent()) {
      if (Files.exists(at.resolve(".git"))) {
        return Optional.of(at);
      }
    }
    return Optional.empty();
  }

  /// `src/main/java/…`, relative to `project`, as it's relative to the repository's root, with `/`
  /// on every platform; as it is where there's no repository.
  static String fromRoot(Path project, String fromProject) {
    var at = project.toAbsolutePath().normalize();
    return repository(at)
        .map(repository -> repository.relativize(at.resolve(fromProject)).toString())
        .orElse(fromProject)
        .replace('\\', '/');
  }
}
