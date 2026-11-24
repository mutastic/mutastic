package io.mutastic.worker;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// Loads classes and resources from the project's own directories before asking its parent, as
/// Gradle's test classpath puts them first. JUnit, the project's dependencies and [Probes] come
/// from the worker's own classpath, through the parent.
///
/// Unnamed, as the build's class path loader is to a test: a named loader's name starts each frame
/// of its classes' stacks (`mutastic-production//org.jsoup.Selector.select(…)`), and a test that
/// reports its caller from a frame's text (jsoup's query warnings) failed here and not there.
abstract class RootsLoader extends ClassLoader {
  private final List<Path> roots;
  private final List<Path> resourceRoots;

  /// `roots` hold the classes it defines; `resourceRoots` are where it finds resources itself,
  /// before its parent.
  RootsLoader(List<Path> roots, List<Path> resourceRoots, ClassLoader parent) {
    super(parent);
    this.roots = List.copyOf(roots);
    this.resourceRoots = List.copyOf(resourceRoots);
  }

  /// The bytes to define for `name`, or null to ask the parent.
  abstract byte @Nullable [] bytesFor(String name) throws ClassNotFoundException;

  /// Called once per class this loader defines.
  void defined(String name, Class<?> loaded) {}

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      var loaded = findLoadedClass(name);
      if (loaded == null) {
        var bytes = bytesFor(name);
        loaded = bytes == null ? super.loadClass(name, false) : define(name, bytes);
      }
      if (resolve) {
        resolveClass(loaded);
      }
      return loaded;
    }
  }

  /// This loader's own class of `name`, for the JDK's lookups that ask for one without going
  /// through [#loadClass]: a package's annotations are read from its `package-info` class with
  /// `loadClass(Module, String)`, which comes here. Without it a package's `@NullMarked` was never
  /// seen.
  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    synchronized (getClassLoadingLock(name)) {
      var loaded = findLoadedClass(name);
      if (loaded != null) {
        return loaded;
      }
      var bytes = bytesFor(name);
      if (bytes == null) {
        throw new ClassNotFoundException(name);
      }
      return define(name, bytes);
    }
  }

  private Class<?> define(String name, byte[] bytes) {
    var loaded = defineClass(name, bytes, 0, bytes.length);
    defined(name, loaded);
    return loaded;
  }

  @Override
  public URL getResource(String name) {
    var own = findResource(name);
    return own != null ? own : super.getResource(name);
  }

  /// The project's own, then the parent's, but for its copy of the project's ([ResourceCopy]).
  @Override
  public Enumeration<URL> getResources(String name) throws IOException {
    var found = resourcesIn(resourceRoots, name);
    found.addAll(ResourceCopy.without(Collections.list(getParent().getResources(name))));
    return Collections.enumeration(found);
  }

  /// Spring's component scan lists package directories, and `application.properties` is a resource:
  /// both come from the roots.
  @Override
  protected @Nullable URL findResource(String name) {
    var found = resourcesIn(resourceRoots, name);
    return found.isEmpty() ? null : found.get(0);
  }

  @Override
  protected Enumeration<URL> findResources(String name) {
    return Collections.enumeration(resourcesIn(resourceRoots, name));
  }

  /// The class file for `name` from the roots, or null if none has it.
  byte @Nullable [] classFile(String name) {
    return classFile(roots, name);
  }

  static byte @Nullable [] classFile(List<Path> roots, String name) {
    var relative = name.replace('.', '/') + ".class";
    for (Path root : roots) {
      var file = root.resolve(relative);
      if (Files.isRegularFile(file)) {
        try {
          return Files.readAllBytes(file);
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        }
      }
    }
    return null;
  }

  private static List<URL> resourcesIn(List<Path> roots, String name) {
    var found = new ArrayList<URL>();
    for (Path root : roots) {
      var file = root.resolve(name);
      if (Files.exists(file)) {
        try {
          found.add(file.toUri().toURL());
        } catch (MalformedURLException e) {
          throw new UncheckedIOException(e);
        }
      }
    }
    return found;
  }
}
