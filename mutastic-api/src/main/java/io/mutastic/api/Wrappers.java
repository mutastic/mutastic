package io.mutastic.api;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/// How a build tool's setup finds the command its build runs with: the build's own wrapper, else
/// the tool on the PATH.
@Internal
public final class Wrappers {
  private Wrappers() {}

  /// Whether this is Windows, where a program's file is named with its kind (`mvn.cmd`) and a build
  /// has a wrapper for it (`mvnw.cmd`, `gradlew.bat`) beside the one for other systems.
  public static final boolean WINDOWS = File.separatorChar == '\\';

  /// The command a build runs with.
  ///
  /// @param command the words a process starts with: the wrapper's file, after `sh` when it isn't
  ///   executable (a checkout can lose the bit); else the tool's name on the PATH, `gradle`
  /// @param file the wrapper, or the tool's file on the PATH
  /// @param onPath whether it's the tool on the PATH, as the build has no wrapper
  public record Tool(List<String> command, Path file, boolean onPath) {
    public Tool {
      command = List.copyOf(command);
    }

    /// The wrapper at `file`: run as it is when it's executable, else with `sh`. On Windows a
    /// wrapper (`gradlew.bat`) runs as it is.
    public static Tool wrapper(Path file) {
      var words =
          WINDOWS || Files.isExecutable(file)
              ? List.of(file.toString())
              : List.of("sh", file.toString());
      return new Tool(words, file, false);
    }
  }

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

  /// The command a build runs with: its `wrapper`, found by [#wrapper(Path, Path, String)], else
  /// `name` on `path` (the PATH variable's value).
  public static Optional<Tool> tool(Optional<Path> wrapper, String name, String path) {
    return wrapper
        .map(Tool::wrapper)
        .or(
            () ->
                file(name, path)
                    .map(file -> new Tool(List.of(file.getFileName().toString()), file, true)));
  }

  /// The `wrapper` nearest `directory`, looking in it and each directory above it: up to `root`,
  /// the top of its build; and past that up to the top of the repository it's in, where a build
  /// nested in another (with settings of its own) finds the wrapper they share. A wrapper run from
  /// a nested build's directory builds that build, as it finds its own files beside itself. Outside
  /// a repository, no further than `root`: one above that belongs to something else.
  public static Optional<Path> wrapper(Path directory, Path root, String wrapper) {
    var top = repository(root).orElse(root.toAbsolutePath().normalize());
    for (var at = directory.toAbsolutePath().normalize(); at != null; at = at.getParent()) {
      var candidate = at.resolve(wrapper);
      if (Files.isRegularFile(candidate)) {
        return Optional.of(candidate);
      }
      if (at.equals(top)) {
        break;
      }
    }
    return Optional.empty();
  }

  /// The top of the repository `directory` is in: the nearest directory from it up with `.git` in
  /// it, a directory or, in a worktree or submodule, a file.
  static Optional<Path> repository(Path directory) {
    for (var at = directory.toAbsolutePath().normalize(); at != null; at = at.getParent()) {
      if (Files.exists(at.resolve(".git"))) {
        return Optional.of(at);
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
    return file(name, path).map(file -> file.getFileName().toString());
  }

  /// The file `name` starts, in the first of `path`'s directories that has it.
  private static Optional<Path> file(String name, String path) {
    return List.of(path.split(File.pathSeparator, -1)).stream()
        .filter(directory -> !directory.isBlank())
        .flatMap(
            directory ->
                EXTENSIONS.stream()
                    .map(ending -> Path.of(directory, name + ending))
                    .filter(Files::isExecutable))
        .findFirst();
  }

  /// In the order Windows tries them (`PATHEXT`).
  private static final List<String> EXTENSIONS =
      WINDOWS ? List.of(".exe", ".bat", ".cmd") : List.of("");

  /// The version of the tool `program` starts, from the name of one of its distribution's jars,
  /// when that's where it is: `lib/gradle-launcher-8.2.1.jar` beside `bin/gradle`, past links, or
  /// under `libexec` as Homebrew keeps it. Empty for a launcher elsewhere (asdf's or SDKMAN's
  /// shims, say): finding out otherwise means running the tool, which takes seconds.
  ///
  /// @param jar a jar's name, with the version as its `version` group
  public static Optional<String> version(Path program, Pattern jar) {
    try {
      var bin = program.toRealPath().getParent();
      var home = bin == null ? null : bin.getParent();
      if (home == null) {
        return Optional.empty();
      }
      var found = new ArrayList<String>();
      for (var lib : List.of(home.resolve("lib"), home.resolve("libexec/lib"))) {
        if (Files.isDirectory(lib)) {
          try (Stream<Path> files = Files.list(lib)) {
            files
                .map(file -> jar.matcher(file.getFileName().toString()))
                .filter(java.util.regex.Matcher::matches)
                .map(matched -> matched.group("version"))
                .findFirst()
                .ifPresent(found::add);
          }
        }
      }
      return found.stream().findFirst();
    } catch (IOException | RuntimeException e) {
      return Optional.empty();
    }
  }
}
