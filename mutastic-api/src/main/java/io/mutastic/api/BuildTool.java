package io.mutastic.api;

import java.nio.file.Path;
import java.util.List;

/// A build tool mutastic reads a project's setup from, found with [java.util.ServiceLoader]: each
/// is asked in turn whether a directory is one of its builds.
@Internal
public interface BuildTool {
  /// `Gradle`: how messages name it.
  String name();

  /// The files that make a directory one of its builds, as a person would look for them:
  /// `settings.gradle(.kts) or build.gradle(.kts)`.
  String buildFiles();

  /// Whether `directory` is one of its builds.
  boolean recognises(Path directory);

  /// Where a module keeps mutastic's run record, logs and reports, so each module keeps its own:
  /// `build/mutastic` for the build in `directory`, `build/mutastic/data` for `:data`.
  Path workDirectory(Path directory, String module);

  /// The modules whose tests can reach `module`'s code: the ones that depend on it, directly or
  /// through others, in the tool's own names for them (`:app`).
  default List<String> dependents(Path directory, String module, Path workDirectory) {
    return List.of();
  }

  /// Says, before mutastic makes anything for it, that `module` isn't one of the build's, when
  /// that's known without building: so a mistyped name leaves no directory behind.
  ///
  /// @param module `:` for the build in `directory`, else the module as the tool names it
  /// @param typed the directory as the person would type it, for the commands a failure suggests
  /// @throws SetupFailed when `module` isn't one of the build's, saying which modules are
  default void checkModule(Path directory, String module, String typed) {}

  /// How `mutastic run` names `directory`, a directory one of its builds is in: by default the
  /// build in it, as a build that runs from any of its directories is named.
  default Address address(Path directory) {
    return new Address(directory, ":");
  }

  /// A build's directory, and a module of it: `:` for the build in the directory itself.
  record Address(Path directory, String module) {}

  /// The module's code and tests, and how its build runs them.
  ///
  /// @param module `:` for the build in `directory`, else the module as the tool names it
  /// @param typed the directory as the person would type it, for the commands a failure suggests
  /// @throws SetupFailed when the build can't be read, saying what to do instead
  Project read(Path directory, String module, Path workDirectory, String typed);

  /// The test suite a run uses unless told otherwise: Gradle's `test` task, Maven's Surefire.
  String DEFAULT_SUITE = "test";

  /// What to read: a module of the build in `directory`, and which of its test suites.
  ///
  /// @param module `:` for the build in `directory`, else the module as the tool names it
  /// @param typed the directory as the person would type it, for the commands a failure suggests
  /// @param suite a Gradle `Test` task by name, or Maven's `integration-test` (Failsafe); else
  ///   [#DEFAULT_SUITE]
  record Request(Path directory, String module, Path workDirectory, String typed, String suite) {}

  /// The module's code and the tests of one of its suites, and how its build runs them. A tool with
  /// one suite has only [#DEFAULT_SUITE].
  ///
  /// @throws SetupFailed when the build has no such suite, saying which it has
  default Project read(Request request) {
    if (!request.suite().equals(DEFAULT_SUITE)) {
      throw new SetupFailed(
          "%s builds have one test suite, test, not %s".formatted(name(), request.suite()));
    }
    return read(request.directory(), request.module(), request.workDirectory(), request.typed());
  }

  /// The module's code and tests from `setup`, a file in [ProjectSetup]'s format the build already
  /// wrote: for an entry point inside the build, such as a build plugin, which needn't run it
  /// again. Named as [#read(Path, String, Path, String)] names it, so their runs compare.
  Project read(Path directory, String module, Path setup);

  /// The build couldn't be read: the message says what to do instead.
  final class SetupFailed extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

    /// What a build says when it has no suite of that name, from its log: the line after `mutastic:
    /// no test suite `, if it said so.
    public static java.util.Optional<SetupFailed> noSuite(java.util.stream.Stream<String> log) {
      var marker = "mutastic: no test suite ";
      return log.filter(line -> line.contains(marker))
          .findFirst()
          .map(
              line ->
                  new SetupFailed(line.substring(line.indexOf(marker) + "mutastic: ".length())));
    }

    /// For someone who ran a command that names a mutant from somewhere else.
    public static final String ID_HINT =
        "A mutant's id belongs to the project its run was on. If yours came from "
            + "another project, run the command that run printed: it names the project's directory"
            + " and module.";

    public SetupFailed(String message) {
      super(message);
    }
  }
}
