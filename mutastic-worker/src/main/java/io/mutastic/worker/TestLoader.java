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

  /// The class this run runs for the test class `name`: its renamed copy, if it's in a package
  /// production code also uses.
  String copyOf(String name) {
    var topLevel = production.splitTopLevel(name);
    return topLevel == null ? name : Renamer.renamed(name, topLevel, run);
  }

  /// A split test is the production loader's, by either name: a copy by its new name (TestNG looks
  /// a copy up that way), or the class as the build has it by its original name. `Class.forName`
  /// must get a class of the name it asked for, so the original name never gives a copy.
  @Override
  protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
    if (production.splitTopLevel(Renamer.original(name)) != null) {
      return production.loadClass(name);
    }
    return super.loadClass(name, resolve);
  }

  @Override
  byte @Nullable [] bytesFor(String name) {
    return classFile(name);
  }
}
