package io.mutastic.worker;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;

/// The project's production classes, loaded once for the worker's life. Each mutant is swapped in
/// by redefinition and swapped back after its tests.
///
/// Gradle runs tests with code and tests on one classpath, so this loader also finds what a test
/// copy defined here needs from the tests: helper classes in packages of their own (fixtures,
/// builders), and every resource, test resources first as Gradle orders them.
///
/// One loader per mutant would be simpler, and wrong: JVM-wide registries keep the first copy of a
/// class. `Security.addProvider` keeps the first loader's provider, so later mutants of that class
/// never run, and look missed.
final class ProductionLoader extends RootsLoader {
  /// Bytes to use instead of the build's: instrumented classes, or a mutant not yet loaded.
  private final Map<String, byte[]> replacements;

  private final Map<String, Class<?>> loaded = new ConcurrentHashMap<>();
  private final List<Path> testRoots;
  private final Set<String> splitTests;

  /// `splitTests` are the top-level test classes in a package production code also uses: they are
  /// defined here, renamed per run (see [Renamer]).
  ProductionLoader(
      List<Path> roots,
      Map<String, byte[]> replacements,
      List<Path> testRoots,
      Set<String> splitTests,
      ClassLoader parent) {
    super("mutastic-production", roots, concat(testRoots, roots), parent);
    this.replacements = new HashMap<>(replacements);
    this.testRoots = List.copyOf(testRoots);
    this.splitTests = Set.copyOf(splitTests);
  }

  @Override
  byte @Nullable [] bytesFor(String name) throws ClassNotFoundException {
    if (Renamer.runOf(name) >= 0) {
      var original = classFile(testRoots, Renamer.original(name));
      if (original == null) {
        throw new ClassNotFoundException(name);
      }
      var internalNames =
          splitTests.stream().map(test -> test.replace('.', '/')).collect(Collectors.toSet());
      return Renamer.rename(original, internalNames, Renamer.runOf(name));
    }
    if (replacements.containsKey(name)) {
      return replacements.get(name);
    }
    var production = classFile(name);
    if (production != null) {
      return production;
    }
    // A test-only class a test copy uses. A test in a production package is run as a renamed copy,
    // and every class reference in a copy is renamed too, so only a string that names a class
    // reaches here by its original name: `@MethodSource("com.example.UtilTest#cases")`, which
    // JUnit loads through the copy's loader. That class is defined once, as the build has it, and
    // its statics last across runs.
    return classFile(testRoots, name);
  }

  private static List<Path> concat(List<Path> first, List<Path> second) {
    var all = new java.util.ArrayList<>(first);
    all.addAll(second);
    return all;
  }

  @Override
  void defined(String name, Class<?> loadedClass) {
    if (Renamer.runOf(name) < 0) {
      loaded.put(name, loadedClass);
    }
  }

  /// The split-package test class `name` belongs to (itself or the class it's nested in), or null.
  @Nullable String splitTopLevel(String name) {
    int dollar = name.indexOf('$');
    var topLevel = dollar < 0 ? name : name.substring(0, dollar);
    return splitTests.contains(topLevel) ? topLevel : null;
  }

  /// Puts mutants in: redefines classes already loaded, and leaves the rest to be defined with the
  /// mutant's bytes if a test loads them.
  void swapIn(Map<String, byte[]> mutants) throws Exception {
    for (var mutant : mutants.entrySet()) {
      var loadedClass = loaded.get(mutant.getKey());
      if (loadedClass != null) {
        Agent.redefine(loadedClass, mutant.getValue());
      } else {
        replacements.put(mutant.getKey(), mutant.getValue());
      }
    }
  }

  /// Back to the build's bytes, whether the class was redefined or first defined as a mutant.
  void swapBack(Set<String> names) throws Exception {
    for (String name : names) {
      replacements.remove(name);
      var loadedClass = loaded.get(name);
      if (loadedClass != null) {
        Agent.redefine(loadedClass, buildBytes(name));
      }
    }
  }

  /// The build's own bytes for a class this loader defined from them, which are still there.
  private byte[] buildBytes(String name) {
    var bytes = classFile(name);
    if (bytes == null) {
      throw new IllegalStateException(
          "the class file for " + name + " went missing during the run");
    }
    return bytes;
  }

  /// After the coverage pass: every instrumented class back to the build's bytes, so mutants run
  /// without the probes' cost.
  void restoreAll() throws Exception {
    swapBack(Set.copyOf(replacements.keySet()));
  }
}
