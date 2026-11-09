package io.mutastic.api;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;

/// How a build tool's setup finds the command its build runs with: the build's own wrapper, else
/// the tool on the PATH.
@Internal
public final class Wrappers {
  private Wrappers() {}

  /// The nearest directory from `directory` up that `isRoot` holds for: the top of the build a
  /// module directory belongs to. `directory` itself when none does, as a build of its own.
  public static Path root(Path directory, Predicate<Path> isRoot) {
    for (var at = directory.toAbsolutePath().normalize(); at != null; at = at.getParent()) {
      if (isRoot.test(at)) {
        return at;
      }
    }
    return directory.toAbsolutePath().normalize();
  }

  /// The executable `wrapper` nearest `directory`, looking in it and each directory above it up to
  /// `root`: a module's own, else the one at the top of its build.
  public static Optional<Path> wrapper(Path directory, Path root, String wrapper) {
    var top = root.toAbsolutePath().normalize();
    for (var at = directory.toAbsolutePath().normalize(); at != null; at = at.getParent()) {
      var candidate = at.resolve(wrapper);
      if (Files.isExecutable(candidate)) {
        return Optional.of(candidate);
      }
      if (at.equals(top)) {
        break;
      }
    }
    return Optional.empty();
  }

  /// Whether `name` is a program in one of `path`'s directories (the PATH variable's value), as
  /// it's named on any system: `gradle`, or `gradle.bat` on Windows.
  public static boolean onPath(String name, String path) {
    return List.of(path.split(File.pathSeparator, -1)).stream()
        .filter(directory -> !directory.isBlank())
        .flatMap(directory -> EXTENSIONS.stream().map(ending -> Path.of(directory, name + ending)))
        .anyMatch(Files::isExecutable);
  }

  private static final List<String> EXTENSIONS = List.of("", ".bat", ".cmd", ".exe");
}
