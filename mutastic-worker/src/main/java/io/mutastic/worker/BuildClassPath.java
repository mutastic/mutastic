package io.mutastic.worker;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;

/// `java.class.path` as the build's test task gives it: the tests, then the code, then what was
/// there, the dependencies.
///
/// The worker loads the project in class loaders of its own, to swap mutants in, so the JVM's own
/// class path holds only the dependencies and the worker. A test that starts a JVM of its own on
/// `java.class.path`, to run the project's `main` as a person would, found none of the project
/// there and failed, as it doesn't in the build: so no mutant could be judged. Given the build's,
/// that JVM runs, with the classes as they're built. No mutant reaches it, and the engine says so
/// (`SeparateJvms`).
final class BuildClassPath {
  private BuildClassPath() {}

  /// The class path from the worker's `--tests=` and `--production=` arguments and its own.
  static String of(String[] args, String own) {
    var entries = new LinkedHashSet<String>();
    entries.addAll(entries(args, "--tests="));
    entries.addAll(entries(args, "--production="));
    entries.addAll(Arrays.asList(own.split(File.pathSeparator, -1)));
    entries.remove("");
    return String.join(File.pathSeparator, entries);
  }

  private static List<String> entries(String[] args, String flag) {
    var found = new ArrayList<String>();
    for (var arg : args) {
      if (arg.startsWith(flag)) {
        found.addAll(Arrays.asList(arg.substring(flag.length()).split(File.pathSeparator, -1)));
      }
    }
    return found;
  }
}
