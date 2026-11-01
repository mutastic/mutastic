package io.mutastic.worker;

import java.nio.file.Path;
import java.util.List;
import org.jspecify.annotations.Nullable;

/// One run's test classes, in a fresh loader whose parent is the production loader, so test statics
/// (a port picked once in a static field) start clean every run, and tests see the swapped
/// production classes. Resources all come from the production loader, which has every directory in
/// Gradle's order, so none is found twice.
final class TestLoader extends RootsLoader {
  private final ProductionLoader production;
  private final int run;

  TestLoader(List<Path> roots, ProductionLoader production, int run) {
    super("mutastic-tests", roots, List.of(), production);
    this.production = production;
    this.run = run;
  }

  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    var topLevel = production.splitTopLevel(Renamer.original(name));
    if (topLevel != null) {
      // By either name: a framework may look the copy up by its new name (TestNG does).
      return production.loadClass(
          Renamer.runOf(name) < 0 ? Renamer.renamed(name, topLevel, run) : name);
    }
    return super.loadClass(name, resolve);
  }

  @Override
  byte @Nullable [] bytesFor(String name) {
    return classFile(name);
  }
}
