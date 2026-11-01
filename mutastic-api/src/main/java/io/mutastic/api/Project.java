package io.mutastic.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/// One module's code and tests, and how its build runs them: what a build-tool integration reads
/// from the module's test task.
///
/// @param name what reports call it, `pricing` or `app/api`
/// @param java the `java` the test task runs, so tests run on the project's own JDK
/// @param jvmArgs the test task's JVM arguments (`--enable-preview`, system properties)
/// @param workingDirectory where the test task runs, since tests read files by relative path
/// @param classes the main class directories: what gets mutated
/// @param resources the main resource directories
/// @param testClasses the test class directories
/// @param testResources the test resource directories
/// @param sources the main source directories, for showing code
/// @param testSources the test source directories, for writing suggested tests in their style
/// @param dependencies the rest of the test runtime classpath, which brings JUnit
/// @param tests which tests the build runs
/// @param environment the environment variables the build sets for its tests, beyond its own
public record Project(
    String name,
    Path java,
    List<String> jvmArgs,
    Path workingDirectory,
    List<Path> classes,
    List<Path> resources,
    List<Path> testClasses,
    List<Path> testResources,
    List<Path> sources,
    List<Path> testSources,
    List<Path> dependencies,
    TestSelection tests,
    Map<String, String> environment) {

  public Project {
    jvmArgs = List.copyOf(jvmArgs);
    classes = List.copyOf(classes);
    resources = List.copyOf(resources);
    testClasses = List.copyOf(testClasses);
    testResources = List.copyOf(testResources);
    sources = List.copyOf(sources);
    testSources = List.copyOf(testSources);
    dependencies = List.copyOf(dependencies);
    environment = Map.copyOf(environment);
  }
}
