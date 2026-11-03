package io.mutastic.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// What a project's tests need from mutastic to run on the JUnit Platform, which the worker drives,
/// beyond what the project has: for TestNG tests with no engine for them, TestNG's engine, and the
/// platform itself when the project has none. They ship beside the worker.
final class TestFrameworks {
  /// The JUnit Platform's jars, by a class in each: launcher, engine API, commons, and what those
  /// use.
  private static final List<String> PLATFORM =
      List.of(
          "org/junit/platform/launcher/core/LauncherFactory.class",
          "org/junit/platform/engine/TestEngine.class",
          "org/junit/platform/commons/util/ReflectionUtils.class",
          "org/opentest4j/TestAbortedException.class");

  /// TestNG's engine, inside mutastic's jar: on no classpath, where a platform would find it.
  private static final String TESTNG_ENGINE = "testng-engine.jar";

  /// Where it's copied out to, once, for the workers that need it.
  private static @Nullable Path testngEngine;

  private TestFrameworks() {}

  /// Whether the tests run in a framework that loads the app in a class loader of its own:
  /// `@QuarkusTest`'s. Its workers run in framework mode: the framework loads the project's classes
  /// from the classpath, and mutants reach its copies by redefinition.
  static boolean loadsTheApp(List<Path> dependencies) {
    return dependencies.stream()
        .map(path -> path.getFileName().toString())
        .anyMatch(name -> name.startsWith("quarkus-junit"));
  }

  /// The jars to add to a worker's classpath for `dependencies`, the project's test classpath: none
  /// unless its tests are TestNG's and nothing runs them on the platform.
  static List<Path> support(List<Path> dependencies) {
    var names = dependencies.stream().map(path -> path.getFileName().toString()).toList();
    boolean testng = names.stream().anyMatch(name -> name.matches("testng-\\d.*\\.jar"));
    boolean engine = names.stream().anyMatch(name -> name.startsWith("testng-engine-"));
    if (!testng || engine) {
      return List.of();
    }
    var support = new ArrayList<Path>();
    support.add(testngEngine());
    if (names.stream().noneMatch(name -> name.startsWith("junit-platform-engine-"))) {
      PLATFORM.forEach(resource -> support.add(jarOf(resource)));
    }
    return support;
  }

  /// TestNG's engine, copied out of mutastic's jar the first time a worker needs it.
  private static synchronized Path testngEngine() {
    if (testngEngine == null) {
      try (var in = TestFrameworks.class.getResourceAsStream(TESTNG_ENGINE)) {
        if (in == null) {
          throw new IllegalStateException(
              "mutastic's TestNG support is missing: no " + TESTNG_ENGINE);
        }
        var file = Files.createTempFile("mutastic-testng-engine", ".jar");
        file.toFile().deleteOnExit();
        Files.copy(in, file, StandardCopyOption.REPLACE_EXISTING);
        testngEngine = file;
      } catch (IOException e) {
        throw new UncheckedIOException(e);
      }
    }
    return testngEngine;
  }

  /// The jar mutastic has `resource` in.
  private static Path jarOf(String resource) {
    var url = TestFrameworks.class.getClassLoader().getResource(resource);
    if (url == null || !url.getProtocol().equals("jar")) {
      throw new IllegalStateException(
          "mutastic's TestNG support is missing: no " + resource + " beside the worker");
    }
    var file = url.getPath().substring(0, url.getPath().indexOf("!/"));
    try {
      return Path.of(new java.net.URI(file));
    } catch (URISyntaxException e) {
      throw new IllegalStateException("can't read " + url, e);
    }
  }
}
