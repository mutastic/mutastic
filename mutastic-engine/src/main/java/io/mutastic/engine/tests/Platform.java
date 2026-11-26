package io.mutastic.engine.tests;

import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

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

  /// The build's own platform with no launcher, which the worker needs to run anything. The build
  /// tools add the build's own launcher, at its engine's version, when its test task runs without
  /// one (Gradle 8 brings its own): this is when that couldn't be resolved. mutastic's own launcher
  /// would be another version, which the platform doesn't run on.
  @Override
  public Optional<String> refusal(TestClasspath classpath) {
    var engine = classpath.version("junit-platform-engine");
    if (engine.isEmpty() || classpath.has("junit-platform-launcher-")) {
      return Optional.empty();
    }
    return Optional.of(
        ("The tests run on the JUnit Platform %1$s, and their classpath has no"
                + " junit-platform-launcher, which mutastic's test worker runs them with: the build"
                + " runs them with a launcher of its own, and mutastic couldn't resolve"
                + " junit-platform-launcher %1$s from the build's repositories. Declare it as a"
                + " test runtime dependency, which Gradle 9 asks for too:\n"
                + "  testRuntimeOnly(\"org.junit.platform:junit-platform-launcher\")")
            .formatted(engine.get()));
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
