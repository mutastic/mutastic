package io.mutastic.engine.tests;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;

/// The JUnit Platform, which the worker drives: mutastic's own when the project has none, as the
/// worker needs it to start at all. They ship beside the worker.
final class Platform implements TestFramework {
  /// The JUnit Platform's jars, by a class in each: launcher, engine API, commons, and what those
  /// use.
  private static final List<String> JARS =
      List.of(
          "org/junit/platform/launcher/core/LauncherFactory.class",
          "org/junit/platform/engine/TestEngine.class",
          "org/junit/platform/commons/util/ReflectionUtils.class",
          "org/opentest4j/TestAbortedException.class");

  @Override
  public List<Path> support(TestClasspath classpath) {
    return classpath.hasPlatform() ? List.of() : JARS.stream().map(Platform::jarOf).toList();
  }

  /// The jar mutastic has `resource` in.
  private static Path jarOf(String resource) {
    var url = Platform.class.getClassLoader().getResource(resource);
    if (url == null || !url.getProtocol().equals("jar")) {
      throw new IllegalStateException(
          "mutastic's JUnit Platform is missing: no " + resource + " beside the worker");
    }
    var file = url.getPath().substring(0, url.getPath().indexOf("!/"));
    try {
      return Path.of(new java.net.URI(file));
    } catch (URISyntaxException e) {
      throw new IllegalStateException("can't read " + url, e);
    }
  }
}
