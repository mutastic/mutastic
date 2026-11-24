package io.mutastic.engine.tests;

/// `@QuarkusTest`: Quarkus loads the app in a class loader of its own, so the worker runs in
/// framework mode.
final class Quarkus implements TestFramework {
  @Override
  public boolean loadsTheApp(TestClasspath classpath) {
    return classpath.has("quarkus-junit");
  }
}
