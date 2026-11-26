package io.mutastic.worker;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.Enumeration;
import org.jspecify.annotations.Nullable;

/// The thread's context class loader for the worker's life, which finds every class and resource
/// through the current run's [TestLoader].
///
/// A build runs every test under one context loader, and libraries key what they make on it: Spring
/// 5 keeps the CGLIB proxy it made of a class by it, and defines the proxy beside the class it
/// extends. With each run's TestLoader as the context loader, a later run in the worker found
/// nothing kept, made the proxy again, and the JVM refused it in the production loader, which
/// outlasts the runs, as a class defined twice. One context loader keeps it found, as in the build.
///
/// It defines nothing itself and keeps nothing of a run: once the next run starts, a class asked
/// for by name is that run's, never the last one's.
final class ContextLoader extends ClassLoader {
  static {
    registerAsParallelCapable();
  }

  private volatile @Nullable ClassLoader current;

  ContextLoader(ClassLoader parent) {
    super(parent);
  }

  /// From now on, classes and resources come from `run`.
  void runWith(ClassLoader run) {
    current = run;
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    var run = current;
    return run == null ? super.loadClass(name, resolve) : Class.forName(name, resolve, run);
  }

  @Override
  public @Nullable URL getResource(String name) {
    var run = current;
    return run == null ? super.getResource(name) : run.getResource(name);
  }

  @Override
  public Enumeration<URL> getResources(String name) throws IOException {
    var run = current;
    return run == null ? super.getResources(name) : run.getResources(name);
  }

  @Override
  public @Nullable InputStream getResourceAsStream(String name) {
    var run = current;
    return run == null ? super.getResourceAsStream(name) : run.getResourceAsStream(name);
  }
}
