package io.mutastic.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarFile;

/// Which of a worker's class path stays on the JDK's own: the worker's jar, and the JUnit
/// Platform's launcher with what it needs, as the worker links to them. Everything else is a
/// library the worker's system class loader defines itself, so a library that looks a class up by
/// its own loader finds the project's (see the worker's `SystemLoader`).
///
/// A jar is the platform's by what it holds, not by its name: a launcher shaded or renamed still
/// holds `org/junit/platform/launcher/`.
final class PlatformJars {
  /// The packages the launcher needs on the class path it is loaded from.
  private static final List<String> PLATFORM =
      List.of(
          "org/junit/platform/launcher/",
          "org/junit/platform/engine/",
          "org/junit/platform/commons/",
          "org/opentest4j/",
          "org/apiguardian/");

  private static final Map<Path, Boolean> SEEN = new ConcurrentHashMap<>();

  private PlatformJars() {}

  /// Whether `entry` of a class path holds any of the platform's packages; never a directory.
  static boolean holdsThePlatform(Path entry) {
    return Files.isRegularFile(entry) && SEEN.computeIfAbsent(entry, PlatformJars::read);
  }

  private static boolean read(Path jar) {
    try (var file = new JarFile(jar.toFile())) {
      return PLATFORM.stream().anyMatch(directory -> holds(file, directory));
    } catch (IOException e) {
      throw new UncheckedIOException("couldn't read " + jar, e);
    }
  }

  /// Whether `file` has an entry in `directory`, whether or not the jar lists directories.
  private static boolean holds(JarFile file, String directory) {
    return file.getEntry(directory) != null
        || file.stream().anyMatch(entry -> entry.getName().startsWith(directory));
  }
}
