package io.mutastic.worker;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.jar.JarFile;
import org.jspecify.annotations.Nullable;

/// The worker JVM's system class loader (`-Djava.system.class.loader`): the JDK's own, with the
/// project's classes and resources behind it once the worker has loaded them.
///
/// The JDK looks some services up with the system class loader, whoever asks: a
/// `System.LoggerFinder`, a `FileSystemProvider`. A build runs tests with the project on the system
/// class path, so a test's `META-INF/services` entry is found there. The worker loads the project
/// in loaders of its own, to swap mutants in, and without this the JDK never looks in them: a test
/// that installs a `LoggerFinder` passes in the build and fails here. Asked for what its parent
/// hasn't got, this asks the production loader, which has the project's directories in the build's
/// order.
public final class SystemLoader extends ClassLoader {
  static {
    registerAsParallelCapable();
  }

  /// The production loader the JDK's lookups reach; null until the worker has one.
  private static volatile @Nullable RootsLoader project;

  /// The `-javaagent` jars the JVM added: mutastic's own, and any the project's test JVM arguments
  /// bring (Mockito's, a coverage agent).
  private final List<JarFile> agents = new CopyOnWriteArrayList<>();

  /// As the JVM calls it, with its application class loader as `parent`.
  public SystemLoader(ClassLoader parent) {
    super(parent);
  }

  /// The JVM calls this to put a `-javaagent` jar on the system class path, and won't start without
  /// it. An agent's classes are then this loader's, as they'd be the JDK's own system loader's.
  @SuppressWarnings("unused")
  void appendToClassPathForInstrumentation(String path) throws IOException {
    agents.add(new JarFile(path));
  }

  /// From now on, what the JDK can't find on the class path is looked for in `loader`; nowhere, for
  /// null.
  static void project(@Nullable RootsLoader loader) {
    project = loader;
  }

  /// An agent's class, then the project's.
  @Override
  protected Class<?> findClass(String name) throws ClassNotFoundException {
    try {
      var agent = fromAgents(name);
      if (agent != null) {
        return agent;
      }
    } catch (IOException e) {
      throw new ClassNotFoundException(name, e);
    }
    var to = project;
    if (to == null) {
      throw new ClassNotFoundException(name);
    }
    return to.loadClass(name);
  }

  /// Only the agents' and the project's own directories: the parent's resources it already found.
  @Override
  protected @Nullable URL findResource(String name) {
    try {
      var agent = agentResources(name);
      if (!agent.isEmpty()) {
        return agent.get(0);
      }
    } catch (IOException e) {
      return null;
    }
    var to = project;
    return to == null ? null : to.findResource(name);
  }

  @Override
  protected Enumeration<URL> findResources(String name) throws IOException {
    var found = agentResources(name);
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

  private List<URL> agentResources(String name) throws IOException {
    var found = new ArrayList<URL>();
    for (var jar : agents) {
      if (jar.getJarEntry(name) != null) {
        found.add(URI.create("jar:" + Path.of(jar.getName()).toUri() + "!/" + name).toURL());
      }
    }
    return found;
  }
}
