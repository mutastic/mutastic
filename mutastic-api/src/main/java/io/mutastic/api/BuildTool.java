package io.mutastic.api;

import java.nio.file.Path;
import java.util.List;

/// A build tool mutastic reads a project's setup from, found with [java.util.ServiceLoader]: each
/// is asked in turn whether a directory is one of its builds.
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

  /// The module's code and tests, and how its build runs them.
  ///
  /// @param module `:` for the build in `directory`, else the module as the tool names it
  /// @param typed the directory as the person would type it, for the commands a failure suggests
  /// @throws SetupFailed when the build can't be read, saying what to do instead
  Project read(Path directory, String module, Path workDirectory, String typed);

  /// The module's code and tests from `setup`, a file in [ProjectSetup]'s format the build already
  /// wrote: for an entry point inside the build, such as a build plugin, which needn't run it
  /// again. Named as [#read(Path, String, Path, String)] names it, so their runs compare.
  Project read(Path directory, String module, Path setup);

  /// The build couldn't be read: the message says what to do instead.
  final class SetupFailed extends RuntimeException {
    @java.io.Serial private static final long serialVersionUID = 1L;

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
