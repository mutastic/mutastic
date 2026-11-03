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
/// @param alongside production classes loaded with the mutated ones but not mutated: another
///   module's own code, when its tests run over this module's mutants
/// @param outputs what another module's classpath has of this one (its class directories, its jar),
///   taken off that classpath when its tests run over this module's mutants
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
    List<Path> alongside,
    List<Path> outputs,
    TestSelection tests,
    Map<String, String> environment) {

  /// The same project, its tests chosen by `chosen`.
  public Project withTests(TestSelection chosen) {
    return new Project(
        name,
        java,
        jvmArgs,
        workingDirectory,
        classes,
        resources,
        testClasses,
        testResources,
        sources,
        testSources,
        dependencies,
        alongside,
        outputs,
        chosen,
        environment);
  }

  /// The project whose mutants are this one's, run by `runner`'s tests, as `runner`'s build runs
  /// them: its JDK, JVM arguments, working directory, tests, environment and classpath, with this
  /// module's outputs taken off it and its own code loaded beside the mutated classes.
  public Project runningTestsOf(Project runner) {
    var mine = new java.util.HashSet<>(outputs);
    var dependencies = runner.dependencies().stream().filter(path -> !mine.contains(path)).toList();
    var beside = new java.util.ArrayList<>(runner.classes());
    beside.addAll(runner.resources());
    return new Project(
        name,
        runner.java(),
        runner.jvmArgs(),
        runner.workingDirectory(),
        classes,
        resources,
        runner.testClasses(),
        runner.testResources(),
        sources,
        runner.testSources(),
        dependencies,
        beside,
        outputs,
        runner.tests(),
        runner.environment());
  }

  public Project {
    jvmArgs = List.copyOf(jvmArgs);
    classes = List.copyOf(classes);
    resources = List.copyOf(resources);
    testClasses = List.copyOf(testClasses);
    testResources = List.copyOf(testResources);
    sources = List.copyOf(sources);
    testSources = List.copyOf(testSources);
    dependencies = List.copyOf(dependencies);
    alongside = List.copyOf(alongside);
    outputs = List.copyOf(outputs);
    environment = Map.copyOf(environment);
  }
}
