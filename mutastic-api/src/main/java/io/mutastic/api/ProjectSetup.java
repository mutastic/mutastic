package io.mutastic.api;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/// The setup file a build tool's own code writes from inside the build (Gradle's init script,
/// Maven's extension): a properties file with what the module's tests run with. One format, so
/// every build tool reads it the same way.
///
/// Keys: `java`, `jvmArgs` (one per line), `workingDirectory`, and path lists joined with the
/// platform's separator: `classes`, `resources`, `testClasses`, `testResources`, `sources`,
/// `testSources`, `dependencies` (the test runtime classpath without the module's own directories
/// or the build's other modules), `alongside` (the build's other modules on that classpath: their
/// class directories, resources or jars). `outputs`: what another module's classpath has of this
/// one. Which tests run: `testIncludes` and `testExcludes` (one class-file pattern per line, see
/// [TestSelection]), `includeTags` and `excludeTags` (one per line). `environment`: one
/// `NAME=value` per line. Keys a build doesn't set are empty.
@Internal
public final class ProjectSetup {
  private ProjectSetup() {}

  /// The project in `file`, named `name` in reports.
  public static Project read(String name, Path file) throws IOException {
    var setup = new Properties();
    try (InputStream in = Files.newInputStream(file)) {
      setup.load(in);
    }
    return new Project(
        name,
        Path.of(required(setup, "java")),
        jvmArgs(setup.getProperty("jvmArgs", "")),
        Path.of(required(setup, "workingDirectory")),
        paths(setup.getProperty("classes")),
        paths(setup.getProperty("resources")),
        paths(setup.getProperty("testClasses")),
        paths(setup.getProperty("testResources")),
        paths(setup.getProperty("sources")),
        paths(setup.getProperty("testSources")),
        paths(setup.getProperty("dependencies")),
        paths(setup.getProperty("alongside")),
        paths(setup.getProperty("outputs")),
        tests(setup),
        environment(lines(setup, "environment")));
  }

  private static TestSelection tests(Properties setup) {
    return new TestSelection(
        lines(setup, "testIncludes"),
        lines(setup, "testExcludes"),
        lines(setup, "includeTags"),
        lines(setup, "excludeTags"));
  }

  private static List<String> lines(Properties setup, String key) {
    return setup
        .getProperty(key, "")
        .lines()
        .map(String::strip)
        .filter(line -> !line.isEmpty())
        .toList();
  }

  private static Map<String, String> environment(List<String> lines) {
    var environment = new LinkedHashMap<String, String>();
    for (String line : lines) {
      int equals = line.indexOf('=');
      if (equals > 0) {
        environment.put(line.substring(0, equals), line.substring(equals + 1));
      }
    }
    return environment;
  }

  private static String required(Properties setup, String key) {
    var value = setup.getProperty(key);
    if (value == null) {
      throw new IllegalStateException("the setup file has no " + key);
    }
    return value;
  }

  /// The tests' JVM arguments, without a coverage tool's agent: it adds members as classes load,
  /// and a class whose shape changed can't have mutants swapped in.
  private static List<String> jvmArgs(String lines) {
    return lines
        .lines()
        .map(String::strip)
        .filter(arg -> !arg.isEmpty() && !coverageAgent(arg))
        .toList();
  }

  /// A coverage tool's agent, by its jar's name: JaCoCo's, `jacocoagent.jar` from Gradle's plugin
  /// and `org.jacoco.agent-0.8.12-runtime.jar` from Maven's `prepare-agent`; Kover's,
  /// `kover-jvm-agent-0.9.1.jar`, and IntelliJ's that it's built on. Once, only Gradle's JaCoCo was
  /// known: under Maven a Quarkus project's run found no test reached anything, and Kover's jar,
  /// which Kover writes in a task of its own before the tests, stopped the worker starting.
  static boolean coverageAgent(String arg) {
    var agent = "-javaagent:";
    if (!arg.startsWith(agent)) {
      return false;
    }
    var jar = arg.substring(agent.length()).split("=", 2)[0];
    var name = jar.substring(Math.max(jar.lastIndexOf('/'), jar.lastIndexOf('\\')) + 1);
    return name.startsWith("jacocoagent")
        || name.startsWith("org.jacoco.agent")
        || name.startsWith("kover-jvm-agent")
        || name.startsWith("intellij-coverage-agent");
  }

  private static List<Path> paths(String joined) {
    return joined == null || joined.isBlank()
        ? List.of()
        : Arrays.stream(joined.split(File.pathSeparator, -1))
            .filter(entry -> !entry.isBlank()) // an empty entry would be the current directory
            .map(Path::of)
            .toList();
  }
}
