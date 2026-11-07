package io.mutastic.worker;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
final class ProductionLoader extends RootsLoader implements MutantInPlace.Classes {
  /// Bytes to use instead of the build's: instrumented classes, or a mutant not yet loaded.
  private final Map<String, byte[]> replacements;

  /// By name, in the order they loaded.
  private final Map<String, Class<?>> loaded =
      java.util.Collections.synchronizedMap(new java.util.LinkedHashMap<>());

  private final List<Path> testRoots;
  private final TestSplit splitTests;

  /// `splitTests` are the top-level test classes in a package production code also uses: they are
  /// defined here, renamed per run (see [Renamer]).
  ProductionLoader(
      List<Path> roots,
      Map<String, byte[]> replacements,
      List<Path> testRoots,
      TestSplit splitTests,
      ClassLoader parent) {
    super("mutastic-production", roots, concat(testRoots, roots), parent);
    this.replacements = new HashMap<>(replacements);
    this.testRoots = List.copyOf(testRoots);
    this.splitTests = splitTests;
  }

  @Override
  byte @Nullable [] bytesFor(String name) throws ClassNotFoundException {
    if (Renamer.runOf(name) >= 0) {
      var original = classFile(testRoots, Renamer.original(name));
      if (original == null) {
        throw new ClassNotFoundException(name);
      }
      var internalNames =
          splitTests.renamed().stream()
              .map(test -> test.replace('.', '/'))
              .collect(Collectors.toSet());
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
    // and every class reference in a copy is renamed too, so only a lookup by name reaches here by
    // its original name: a string (`@MethodSource("com.example.UtilTest#cases")`) or a framework's
    // scan of the class files (Spring's, for a test `@Configuration`). That class is defined once,
    // as the build has it, and its statics last across runs.
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

  /// The production classes loaded so far, in order: not test classes, whose statics belong to a
  /// test's run.
  List<String> loadedFromTheBuild() {
    synchronized (loaded) {
      return loaded.keySet().stream().filter(name -> classFile(name) != null).toList();
    }
  }

  /// The split-package test class `name` belongs to (itself or the class it's nested in), or null.
  @Nullable String splitTopLevel(String name) {
    int dollar = name.indexOf('$');
    var topLevel = dollar < 0 ? name : name.substring(0, dollar);
    return splitTests.renamed().contains(topLevel) ? topLevel : null;
  }

  /// Whether `name` is a class the JDK makes for a service ([TestSplit#shared]): this loader's
  /// alone, by its own name, for every run.
  boolean shared(String name) {
    return splitTests.shared().contains(name);
  }

  /// Puts mutants in: redefines classes already loaded, and leaves the rest to be defined with the
  /// mutant's bytes if a test loads them.
  @Override
  public void swapIn(Map<String, byte[]> mutants) throws Exception {
    for (var mutant : mutants.entrySet()) {
      var loadedClass = loaded.get(mutant.getKey());
      if (loadedClass != null) {
        Agent.redefine(loadedClass, mutant.getValue());
      } else {
        replacements.put(mutant.getKey(), mutant.getValue());
      }
    }
  }

  /// A renamed copy's bytecode, for frameworks that read a class as a resource: Spring reads a test
  /// class's `@Import`s that way, by the copy's name, and the copy has no file.
  @Override
  protected @Nullable URL findResource(String name) {
    var className =
        name.endsWith(".class") ? name.substring(0, name.length() - 6).replace('/', '.') : "";
    if (Renamer.runOf(className) < 0) {
      return super.findResource(name);
    }
    try {
      var bytes = bytesFor(className);
      return bytes == null ? null : new URL(null, "mutastic-copy:" + name, new Bytes(bytes));
    } catch (ClassNotFoundException | MalformedURLException e) {
      return null;
    }
  }

  /// Serves one class's bytes as a URL's content.
  private static final class Bytes extends URLStreamHandler {
    private final byte[] content;

    Bytes(byte[] content) {
      this.content = content;
    }

    @Override
    protected URLConnection openConnection(URL url) {
      return new URLConnection(url) {
        @Override
        public void connect() {}

        @Override
        public InputStream getInputStream() {
          return new ByteArrayInputStream(content);
        }
      };
    }
  }

  /// Back to the build's bytes, whether the class was redefined or first defined as a mutant.
  @Override
  public void swapBack(Set<String> names) throws Exception {
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
