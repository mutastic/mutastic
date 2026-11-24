package io.mutastic.engine.tests;

import java.nio.file.Path;
import java.util.List;

/// A project's test classpath, as the frameworks read it: by its jars' file names.
record TestClasspath(List<Path> jars) {
  TestClasspath {
    jars = List.copyOf(jars);
  }

  /// The jars' file names, `junit-4.13.2.jar`.
  List<String> names() {
    return jars.stream()
        .map(Path::getFileName)
        .map(name -> name == null ? "" : name.toString())
        .toList();
  }

  /// Whether a jar's name starts with `prefix`: `junit-platform-engine-`.
  boolean has(String prefix) {
    return names().stream().anyMatch(name -> name.startsWith(prefix));
  }

  /// Whether a jar's name is the whole of `regex`: `junit-4\\..*\\.jar`.
  boolean hasMatching(String regex) {
    return names().stream().anyMatch(name -> name.matches(regex));
  }

  /// Whether the build runs its tests on the JUnit Platform: it has the platform's engine API.
  boolean hasPlatform() {
    return has("junit-platform-engine-");
  }
}
