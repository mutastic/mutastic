package io.mutastic.engine;

import io.mutastic.api.Project;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/// The JDK services a project provides that the JDK looks up on the system class path, in
/// `META-INF/services` of its own class and resource directories.
///
/// A build runs tests with the project on the system class path, so the JDK finds them there.
/// mutastic's worker loads the project in class loaders of its own, to swap mutants in, so the JDK
/// doesn't: a test that installs a `System.LoggerFinder` to see what the code logs passes in the
/// build and fails under mutastic. Saying so, when the tests fail, is the difference between "your
/// tests are red" and "your tests depend on how they're loaded".
final class SystemServices {
  /// The services the JDK finds with the system class loader, not the caller's.
  static final List<String> LOOKED_UP_BY_THE_JDK =
      List.of(
          "java.lang.System$LoggerFinder",
          "java.nio.file.spi.FileSystemProvider",
          "java.nio.charset.spi.CharsetProvider",
          "java.nio.channels.spi.SelectorProvider",
          "java.net.spi.InetAddressResolverProvider",
          "java.net.spi.URLStreamHandlerProvider");

  private SystemServices() {}

  /// The ones the project's own directories provide, in the order of [#LOOKED_UP_BY_THE_JDK].
  static List<String> providedBy(Project project) {
    var directories =
        Stream.of(
                project.testResources(),
                project.testClasses(),
                project.resources(),
                project.classes())
            .flatMap(List::stream)
            .toList();
    return LOOKED_UP_BY_THE_JDK.stream()
        .filter(
            service ->
                directories.stream()
                    .anyMatch(
                        directory ->
                            Files.isRegularFile(
                                directory.resolve(Path.of("META-INF", "services", service)))))
        .toList();
  }
}
