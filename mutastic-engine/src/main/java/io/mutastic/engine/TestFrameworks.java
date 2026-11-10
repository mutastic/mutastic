package io.mutastic.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/// What a project's tests need from mutastic to run on the JUnit Platform, which the worker drives,
/// beyond what the project has: an engine for TestNG's tests, or for JUnit 4's when the build runs
/// them without the platform (Gradle's `useJUnit()`), and the platform itself when the project has
/// none, which the worker needs to start at all. They ship beside the worker.
final class TestFrameworks {
  /// The JUnit Platform's jars, by a class in each: launcher, engine API, commons, and what those
  /// use.
  private static final List<String> PLATFORM =
      List.of(
          "org/junit/platform/launcher/core/LauncherFactory.class",
          "org/junit/platform/engine/TestEngine.class",
          "org/junit/platform/commons/util/ReflectionUtils.class",
          "org/opentest4j/TestAbortedException.class");

  /// The engines, inside mutastic's jar: on no classpath, where a platform would find them.
  enum Engine {
    TESTNG("testng-engine.jar"),
    JUNIT4("vintage-engine.jar");

    private final String resource;

    Engine(String resource) {
      this.resource = resource;
    }
  }

  /// Where each engine is copied out to, once, for the workers that need it.
  private static final java.util.Map<Engine, Path> COPIED = new java.util.EnumMap<>(Engine.class);

  private TestFrameworks() {}

  /// Whether the tests run in a framework that loads the app in a class loader of its own:
  /// `@QuarkusTest`'s. Its workers run in framework mode: the framework loads the project's classes
  /// from the classpath, and mutants reach its copies by redefinition.
  static boolean loadsTheApp(List<Path> dependencies) {
    return dependencies.stream()
        .map(path -> path.getFileName().toString())
        .anyMatch(name -> name.startsWith("quarkus-junit"));
  }

  /// The jars to add to a worker's classpath for `dependencies`, the project's test classpath: an
  /// engine for TestNG's or JUnit 4's tests when nothing runs them on the platform, and the
  /// platform when there's none.
  static List<Path> support(List<Path> dependencies) {
    var support = new ArrayList<Path>();
    engineNeeded(dependencies).ifPresent(engine -> support.add(copied(engine)));
    var names = dependencies.stream().map(path -> path.getFileName().toString()).toList();
    if (names.stream().noneMatch(name -> name.startsWith("junit-platform-engine-"))) {
      PLATFORM.forEach(resource -> support.add(jarOf(resource)));
    }
    return support;
  }

  /// The engine `dependencies` need and haven't got: TestNG's for TestNG, the vintage engine for
  /// JUnit 4 on a classpath with no platform, where the build runs it with JUnit 4's own runner.
  static java.util.Optional<Engine> engineNeeded(List<Path> dependencies) {
    var names = dependencies.stream().map(path -> path.getFileName().toString()).toList();
    if (names.stream().anyMatch(name -> name.matches("testng-\\d.*\\.jar"))) {
      return names.stream().anyMatch(name -> name.startsWith("testng-engine-"))
          ? java.util.Optional.empty()
          : java.util.Optional.of(Engine.TESTNG);
    }
    boolean junit4 = names.stream().anyMatch(name -> name.matches("junit-4\\..*\\.jar"));
    boolean platform = names.stream().anyMatch(name -> name.startsWith("junit-platform-engine-"));
    return junit4 && !platform ? java.util.Optional.of(Engine.JUNIT4) : java.util.Optional.empty();
  }

  /// The engine, copied out of mutastic's jar the first time a worker needs it.
  private static synchronized Path copied(Engine engine) {
    var copied = COPIED.get(engine);
    if (copied == null) {
      try (var in = TestFrameworks.class.getResourceAsStream(engine.resource)) {
        if (in == null) {
          throw new IllegalStateException(
              "mutastic's test engines are missing: no " + engine.resource);
        }
        copied =
            Files.createTempFile(
                "mutastic-" + engine.name().toLowerCase(java.util.Locale.ROOT), ".jar");
        copied.toFile().deleteOnExit();
        Files.copy(in, copied, StandardCopyOption.REPLACE_EXISTING);
        COPIED.put(engine, copied);
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return copied;
  }

  /// The jar mutastic has `resource` in.
  private static Path jarOf(String resource) {
    var url = TestFrameworks.class.getClassLoader().getResource(resource);
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
