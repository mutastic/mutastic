package io.mutastic.cli;

import io.mutastic.api.BuildTool;
import io.mutastic.api.Project;
import io.mutastic.api.RealPath;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/// Which file each `--at` names, and in a build of several modules which module it's in. A path is
/// taken as given when it's absolute, else from where mutastic runs, from the build's directory or
/// from the module's; `\` and `/` both separate its parts. The file must be one of the module's
/// sources: a test, or a file elsewhere, has no mutant to run.
final class AtFiles {
  private AtFiles() {}

  /// Lines of one of the module's source files, asked for by `--at`.
  ///
  /// @param file the file, absolute with its links resolved, as [io.mutastic.report.SourceView]
  ///   names a mutant's
  /// @param shown the file from the project, `/` between its parts, as a card names it
  record Lines(Path file, String shown, At at) {
    /// `src/main/java/com/example/pricing/Pricing.java:17-20`: as `--at` takes it, from the
    /// project.
    String typed() {
      return at.typed(shown);
    }

    /// `Pricing.java`.
    String name() {
      return String.valueOf(file.getFileName());
    }

    /// `line 17 of Pricing.java`, `lines 17–20 of Pricing.java`.
    String named() {
      return (at.from() == at.to()
              ? "line " + at.from()
              : "lines %d–%d".formatted(at.from(), at.to()))
          + " of "
          + name();
    }
  }

  /// The command on the module its first file is in, when it names no module and its directory is a
  /// build of several: `run --at=app/src/main/java/com/example/Checkout.java:42` from the build's
  /// top runs `:app`, as `run` in a directory inside a build walks up to it. A module's directory
  /// is the last one, on the way down to the file, that's a build of its own or has the file in its
  /// `src`; none below the top is the top's own module.
  static Main.Command onItsModule(Main.Command command) {
    if (command.at().isEmpty() || !command.module().equals(":") || command.setup().isPresent()) {
      return command;
    }
    var build = RealPath.of(command.directory());
    var tool = BuildTools.find(build);
    var here = Path.of("").toAbsolutePath();
    var file =
        candidates(command.at().getFirst().path(), List.of(here, build))
            .filter(Files::isRegularFile)
            .map(RealPath::of)
            .filter(found -> found.startsWith(build))
            .findFirst();
    if (tool.isEmpty() || file.isEmpty()) {
      return command;
    }
    return moduleDirectory(build, file.get(), tool.get())
        .map(directory -> command.on(command.directory(), tool.get().moduleAt(build, directory)))
        .orElse(command);
  }

  /// The directory of the module below `build` that `file` is in, if it's not `build`'s own.
  private static Optional<Path> moduleDirectory(Path build, Path file, BuildTool tool) {
    Optional<Path> found = Optional.empty();
    var directory = build;
    for (var part : build.relativize(file.getParent())) {
      directory = directory.resolve(part);
      if (part.toString().equals("src")) {
        break; // a module's sources, not a module of their own
      }
      if (tool.recognises(directory) || file.startsWith(directory.resolve("src"))) {
        found = Optional.of(directory);
      }
    }
    return found;
  }

  /// Each `--at`'s file in the module's sources, with its lines.
  ///
  /// @param directory the build's directory, which the files are shown from
  static List<Lines> of(List<At> asked, Path directory, Project project) {
    var roots = roots(project);
    var bases = List.of(Path.of("").toAbsolutePath(), directory, project.workingDirectory());
    var all = new ArrayList<Lines>();
    for (var at : asked) {
      var file = file(at, bases, roots, directory);
      all.add(new Lines(file, shown(RealPath.of(directory), file), at));
    }
    return List.copyOf(all);
  }

  /// The file `at` names that's in one of `roots`; refused when it's in none, or isn't there.
  private static Path file(At at, List<Path> bases, List<Path> roots, Path directory) {
    var there =
        candidates(at.path(), bases).filter(Files::isRegularFile).map(RealPath::of).toList();
    return there.stream()
        .filter(file -> roots.stream().anyMatch(file::startsWith))
        .findFirst()
        .orElseThrow(() -> refused(at, there, roots, RealPath.of(directory)));
  }

  /// The files `typed` can name: itself when it's absolute, else from each of `bases`, its parts
  /// split at either separator.
  private static Stream<Path> candidates(String typed, List<Path> bases) {
    var path = Path.of(typed.replace('\\', '/'));
    var all = path.isAbsolute() ? Stream.of(path) : bases.stream().map(base -> base.resolve(path));
    return all.map(Path::normalize).distinct();
  }

  /// The module's source directories that are there, with their links resolved.
  private static List<Path> roots(Project project) {
    return project.sources().stream().filter(Files::isDirectory).map(RealPath::of).toList();
  }

  /// Why `at` names no source file: it isn't there, or it isn't in the module's sources.
  private static Main.UsageException refused(
      At at, List<Path> there, List<Path> roots, Path directory) {
    if (there.isEmpty()) {
      return new Main.UsageException(
          Failure.USAGE,
          "there's no file %s, from here or from the project: --at takes a path from either"
              .formatted(at.path()));
    }
    var named = roots.stream().map(root -> shown(directory, root)).toList();
    return new Main.UsageException(
        OUTSIDE_SOURCES,
        "%s isn't in the module's sources: --at takes a line of the code mutastic mutates, in %s"
            .formatted(
                shown(directory, there.getFirst()),
                named.isEmpty() ? "none here" : Options.listed(named, "or")));
  }

  /// What an `--at` outside the module's sources is called.
  static final String OUTSIDE_SOURCES = "outside-sources";

  /// `src/main/java`, from the project; the whole path when it's outside it.
  private static String shown(Path directory, Path file) {
    return file.startsWith(directory) && !file.equals(directory)
        ? Main.slashed(directory.relativize(file))
        : file.toString();
  }
}
