package io.mutastic.engine;

import io.mutastic.api.Project;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/// The project's own resources, where the libraries its tests use can find them. A library looks a
/// resource up with its own class loader (`ResourceBundle.getBundle(name, locale)` does), and on
/// the build's one test classpath that finds the project's resources as well as its own. The worker
/// loads the project in class loaders of its own, so mutants can be swapped in, and a library's
/// loader can't see those: so the project's resources are linked into a directory of their own,
/// which goes on the worker's classpath ahead of the libraries, where the build has them.
///
/// Not class files, which must come from the worker's loaders for a mutant to be what runs; and
/// nothing in `META-INF`, whose files name classes for a library to load (services, Spring's
/// factories), which a library's loader can't.
final class ResourceCopy {
  private static final String META_INF = "META-INF";

  private ResourceCopy() {}

  /// The project's resources, linked into `directory` afresh: its tests' before its own, as the
  /// build orders them, and the first of a name wins. Copied where a file can't be linked.
  static Path of(Project project, Path directory) {
    var roots =
        Stream.of(
                project.testResources(),
                project.testClasses(),
                project.resources(),
                project.classes(),
                project.alongside())
            .flatMap(List::stream)
            .filter(Files::isDirectory)
            .toList();
    try {
      delete(directory);
      Files.createDirectories(directory);
      for (var root : roots) {
        linkResources(root, directory);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
    return directory;
  }

  private static void linkResources(Path root, Path directory) throws IOException {
    try (var files = Files.walk(root)) {
      for (var file : files.filter(Files::isRegularFile).toList()) {
        var relative = root.relativize(file);
        if (isResource(relative)) {
          link(file, directory.resolve(relative.toString()));
        }
      }
    }
  }

  /// Whether a file, by its path under its root, is one a library may look up from the project.
  static boolean isResource(Path relative) {
    return !relative.toString().endsWith(".class")
        && !relative.getName(0).toString().equals(META_INF);
  }

  private static void link(Path file, Path copy) throws IOException {
    if (Files.exists(copy)) {
      return;
    }
    Files.createDirectories(copy.getParent());
    try {
      Files.createLink(copy, file);
    } catch (IOException | UnsupportedOperationException e) {
      Files.copy(file, copy);
    }
  }

  /// A last run's copy: links, so the project's own files are untouched.
  private static void delete(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }
    try (var paths = Files.walk(directory)) {
      for (var path : paths.sorted(Comparator.reverseOrder()).toList()) {
        Files.delete(path);
      }
    }
  }
}
