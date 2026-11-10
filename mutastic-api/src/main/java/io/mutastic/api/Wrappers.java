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

  /// Whether this is Windows, where a program's file is named with its kind (`mvn.cmd`) and a build
  /// has a wrapper for it (`mvnw.cmd`, `gradlew.bat`) beside the one for other systems.
  public static final boolean WINDOWS = File.separatorChar == '\\';

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

  /// Whether `name` is a program in one of `path`'s directories (the PATH variable's value).
  public static boolean onPath(String name, String path) {
    return program(name, path).isPresent();
  }

  /// `name` as a process is started with it when it's in one of `path`'s directories: `mvn`, or on
  /// Windows the file that's there, `mvn.cmd`, since Windows only adds `.exe` to a name itself. A
  /// directory's own `mvn`, a script for other systems, isn't one Windows can run.
  public static Optional<String> program(String name, String path) {
    return List.of(path.split(File.pathSeparator, -1)).stream()
        .filter(directory -> !directory.isBlank())
        .flatMap(
            directory ->
                EXTENSIONS.stream()
                    .map(ending -> name + ending)
                    .filter(file -> Files.isExecutable(Path.of(directory, file))))
        .findFirst();
  }

  /// In the order Windows tries them (`PATHEXT`).
  private static final List<String> EXTENSIONS =
      WINDOWS ? List.of(".exe", ".bat", ".cmd") : List.of("");
}
