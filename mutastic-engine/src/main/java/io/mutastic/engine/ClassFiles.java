package io.mutastic.engine;

import java.io.UncheckedIOException;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassHierarchyResolver;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;

/// The `ClassFile` context every transform and verification uses. Recomputing stack maps and
/// verifying both need supertypes, and the default resolver only sees the engine's own classpath,
/// so a project's Spring types come back "Could not resolve class". This one also reads class files
/// from the project's directories and dependencies, without loading them.
final class ClassFiles {
  private ClassFiles() {}

  static ClassFile resolvingFrom(List<Path> classpath) {
    var urls = classpath.stream().map(ClassFiles::url).toArray(URL[]::new);
    var resources = new URLClassLoader("mutastic-hierarchy", urls, null);
    var resolver =
        ClassHierarchyResolver.defaultResolver()
            .orElse(ClassHierarchyResolver.ofResourceParsing(resources))
            .cached();
    return ClassFile.of(ClassFile.ClassHierarchyResolverOption.of(resolver));
  }

  /// The same classes, for what a filter asks of them: the JDK's platform classes, then these. Not
  /// the engine's own classpath, which says nothing about the project.
  static Supertypes supertypesFrom(List<Path> classpath) {
    var urls = classpath.stream().map(ClassFiles::url).toArray(URL[]::new);
    return Supertypes.in(
        new URLClassLoader("mutastic-supertypes", urls, ClassLoader.getPlatformClassLoader()));
  }

  private static URL url(Path entry) {
    try {
      return entry.toUri().toURL();
    } catch (MalformedURLException e) {
      throw new UncheckedIOException(e);
    }
  }
}
