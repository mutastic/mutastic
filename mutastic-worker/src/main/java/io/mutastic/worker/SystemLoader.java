package io.mutastic.worker;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.security.SecureClassLoader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import org.jspecify.annotations.Nullable;

/// The worker JVM's system class loader (`-Djava.system.class.loader`): the JUnit Platform's
/// launcher behind it, as the worker links to that; then the project's libraries, which it defines
/// itself; then, once the worker has loaded them, the project's classes and resources.
///
/// A build runs tests with the project and its libraries on one class path, so a library that looks
/// a class up by its own loader finds the project's: Java serialization resolves a class with the
/// latest loader on the stack (spring-core's `SerializationUtils.deserialize` of a `Vet`), and
/// `Class.forName(name)` with the caller's. With the libraries on the JDK's own class path, below
/// which the worker's loaders sit, those lookups never reached the project, and a test that passes
/// in the build failed here. So the libraries are this loader's, and what they can't find, it looks
/// for in the project.
///
/// The JDK also looks some services up with the system class loader, whoever asks: a
/// `System.LoggerFinder`, a `FileSystemProvider`. A test's `META-INF/services` entry is found the
/// same way.
public final class SystemLoader extends SecureClassLoader {
  static {
    registerAsParallelCapable();
  }

  /// The production loader the lookups reach; null until the worker has one.
  private static volatile @Nullable RootsLoader project;

  /// Set while this thread asks the project, which asks back here for what it hasn't got.
  private static final ThreadLocal<Boolean> ASKING_THE_PROJECT =
      ThreadLocal.withInitial(() -> false);

  /// The `-javaagent` jars the JVM added: mutastic's own, and any the project's test JVM arguments
  /// bring (Mockito's, a coverage agent).
  private final List<JarFile> agents = new CopyOnWriteArrayList<>();

  private final Libraries libraries;

  /// As the JVM calls it, with its application class loader as `parent`: the libraries are those
  /// the engine names in [Libraries#PROPERTY], and `java.class.path` lists them after the
  /// launcher's, as a build's does, for what reads it to compile or scan.
  public SystemLoader(ClassLoader parent) {
    this(parent, System.getProperty(Libraries.PROPERTY, ""));
    var named = System.getProperty(Libraries.PROPERTY, "");
    if (!named.isEmpty()) {
      System.setProperty(
          "java.class.path", System.getProperty("java.class.path") + File.pathSeparator + named);
    }
  }

  /// With `libraries`, a class path's entries.
  SystemLoader(ClassLoader parent, String libraries) {
    super(parent);
    this.libraries = Libraries.of(libraries);
  }

  /// The JVM calls this to put a `-javaagent` jar on the system class path, and won't start without
  /// it. An agent's classes are then this loader's, as they'd be the JDK's own system loader's.
  @SuppressWarnings("unused")
  void appendToClassPathForInstrumentation(String path) throws IOException {
    agents.add(new JarFile(path));
  }

  /// From now on, what the libraries can't find is looked for in `loader`; nowhere, for null.
  static void project(@Nullable RootsLoader loader) {
    project = loader;
  }

  /// The parent's, an agent's or a library's, and failing those the project's. The project is asked
  /// outside this loader's lock for the name: the project asks back here for what it hasn't got,
  /// holding its own, so asking it with this one held could deadlock two threads looking up one
  /// missing name from either side.
  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    try {
      return super.loadClass(name, resolve);
    } catch (ClassNotFoundException notHere) {
      var to = project;
      if (to == null || ASKING_THE_PROJECT.get()) {
        throw notHere;
      }
      ASKING_THE_PROJECT.set(true);
      try {
        return to.loadClass(name);
      } finally {
        ASKING_THE_PROJECT.set(false);
      }
    }
  }

  /// An agent's class, then a library's.
  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    try {
      var agent = fromAgents(name);
      if (agent != null) {
        return agent;
      }
      var library = libraries.classFile(name);
      if (library == null) {
        throw new ClassNotFoundException(name);
      }
      definePackageOf(name, library);
      return defineClass(name, library.bytes(), 0, library.bytes().length, library.source());
    } catch (IOException e) {
      throw new ClassNotFoundException(name, e);
    }
  }

  /// The package a library's class is in, once, with what its jar's manifest says of it, sealed to
  /// the jar if the manifest seals it.
  private void definePackageOf(String name, Libraries.Found library) {
    int dot = name.lastIndexOf('.');
    if (dot < 0 || getDefinedPackage(name.substring(0, dot)) != null) {
      return;
    }
    var manifest = library.manifest();
    var section = library.entry().substring(0, library.entry().lastIndexOf('/') + 1);
    try {
      definePackage(
          name.substring(0, dot),
          Libraries.attribute(manifest, section, Attributes.Name.SPECIFICATION_TITLE),
          Libraries.attribute(manifest, section, Attributes.Name.SPECIFICATION_VERSION),
          Libraries.attribute(manifest, section, Attributes.Name.SPECIFICATION_VENDOR),
          Libraries.attribute(manifest, section, Attributes.Name.IMPLEMENTATION_TITLE),
          Libraries.attribute(manifest, section, Attributes.Name.IMPLEMENTATION_VERSION),
          Libraries.attribute(manifest, section, Attributes.Name.IMPLEMENTATION_VENDOR),
          "true".equalsIgnoreCase(Libraries.attribute(manifest, section, Attributes.Name.SEALED))
              ? library.source().getLocation()
              : null);
    } catch (IllegalArgumentException definedMeanwhile) {
      // Another thread defined it first, with the same jar's attributes.
    }
  }

  /// Only the agents', the libraries' and the project's own: the parent's resources it already
  /// found.
  @Override
  protected @Nullable URL findResource(String name) {
    try {
      var own = ownResources(name);
      if (!own.isEmpty()) {
        return own.get(0);
      }
    } catch (IOException e) {
      return null;
    }
    var to = project;
    return to == null ? null : to.findResource(name);
  }

  /// The parent's and then the project's, and once the project is here, not the parent's copy of
  /// its resources too ([ResourceCopy]).
  @Override
  public Enumeration<URL> getResources(String name) throws IOException {
    var found = Collections.list(super.getResources(name));
    return Collections.enumeration(project == null ? found : ResourceCopy.without(found));
  }

  @Override
  protected Enumeration<URL> findResources(String name) throws IOException {
    var found = ownResources(name);
    var to = project;
    if (to != null) {
      found.addAll(Collections.list(to.findResources(name)));
    }
    return Collections.enumeration(found);
  }

  /// The class from an agent's jar, defined here, or null if no agent has it.
  private @Nullable Class<?> fromAgents(String name) throws IOException {
    var entry = name.replace('.', '/') + ".class";
    for (var jar : agents) {
      var found = jar.getJarEntry(entry);
      if (found != null) {
        try (var in = jar.getInputStream(found)) {
          var bytes = in.readAllBytes();
          return defineClass(name, bytes, 0, bytes.length);
        }
      }
    }
    return null;
  }

  /// The agents' resources `name`, then the libraries'.
  private List<URL> ownResources(String name) throws IOException {
    var found = new ArrayList<URL>();
    for (var jar : agents) {
      if (jar.getJarEntry(name) != null) {
        found.add(URI.create("jar:" + Path.of(jar.getName()).toUri() + "!/" + name).toURL());
      }
    }
    found.addAll(libraries.resources(name));
    return found;
  }
}
