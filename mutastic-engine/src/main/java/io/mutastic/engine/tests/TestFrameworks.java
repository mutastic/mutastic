package io.mutastic.engine.tests;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/// What a project's tests need from mutastic to run on the JUnit Platform, which the worker drives,
/// beyond what the project has: an engine for TestNG's tests ([TestNg]), or for JUnit 4's when the
/// build runs them without the platform ([Vintage]), the platform itself when the project has none
/// ([Platform]), and framework mode for a framework that loads the app itself ([Quarkus]). The rest
/// of the engine names no framework, only this.
public final class TestFrameworks {
  /// In the order their jars go on a worker's classpath: an engine, then the platform it runs on.
  private static final List<TestFramework> FRAMEWORKS =
      List.of(new TestNg(), new Vintage(), new Platform(), new Quarkus());

  private final TestClasspath classpath;

  private TestFrameworks(TestClasspath classpath) {
    this.classpath = classpath;
  }

  /// The frameworks of the tests whose classpath is `dependencies`, the project's test classpath.
  public static TestFrameworks of(List<Path> dependencies) {
    return new TestFrameworks(new TestClasspath(dependencies));
  }

  /// Whether the tests run in a framework that loads the app in a class loader of its own:
  /// `@QuarkusTest`'s. Its workers run in framework mode: the framework loads the project's classes
  /// from the classpath, and mutants reach its copies by redefinition.
  public boolean loadsTheApp() {
    return FRAMEWORKS.stream().anyMatch(framework -> framework.loadsTheApp(classpath));
  }

  /// The jars to add to a worker's classpath: an engine for TestNG's or JUnit 4's tests when
  /// nothing runs them on the platform, and the platform when there's none.
  public List<Path> support() {
    return FRAMEWORKS.stream().flatMap(framework -> framework.support(classpath).stream()).toList();
  }

  /// What the tests failing to start as `failure` means, from the first framework that knows: what
  /// to change. Empty when none does.
  public Optional<String> unstarted(StartFailure failure) {
    return FRAMEWORKS.stream()
        .flatMap(framework -> framework.unstarted(classpath, failure).stream())
        .findFirst();
  }

  /// Why a worker can't run these tests, from the first framework that says: what to add or change.
  /// Empty when it can.
  public Optional<String> refusal() {
    return FRAMEWORKS.stream()
        .flatMap(framework -> framework.refusal(classpath).stream())
        .findFirst();
  }
}
