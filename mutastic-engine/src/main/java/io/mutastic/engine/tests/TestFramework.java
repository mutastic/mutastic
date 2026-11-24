package io.mutastic.engine.tests;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/// A test framework a worker can run a project's tests with: what it needs from mutastic, beyond
/// what the project has, and how the worker must run it. One part per framework, so what a
/// framework needs is decided in one place ([TestFrameworks] lists them).
interface TestFramework {
  /// The jars a worker needs on its classpath for this framework's tests that `classpath` hasn't
  /// got: none, unless the framework says otherwise.
  default List<Path> support(TestClasspath classpath) {
    return List.of();
  }

  /// Whether this framework loads the app in a class loader of its own, so the worker runs in
  /// framework mode.
  default boolean loadsTheApp(TestClasspath classpath) {
    return false;
  }

  /// What `failure`, the worker's tests failing to start, means for this framework, when it's this
  /// framework's doing: what to change, said to the person. Empty when it isn't.
  default Optional<String> unstarted(TestClasspath classpath, StartFailure failure) {
    return Optional.empty();
  }
}
