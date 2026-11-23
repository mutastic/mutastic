package io.mutastic.engine;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.Attributes;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.constantpool.ClassEntry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/// Whether a module's test classes declare a test at all, read from their bytecode. A test source
/// set of only helpers (a `Fixtures` class) runs no test on the JUnit Platform, and neither do
/// tests mutastic can't run: JUnit 4's with no engine for them, or every one filtered out. The
/// first is a module with no tests, scored as one; the second must stop the run, as scoring it
/// would blame tests mutastic never saw. Only this tells them apart: the platform finds nothing in
/// either.
///
/// A class may hold tests when it, or any of its methods, carries a test framework's annotation
/// (`@Test` from JUnit 5, JUnit 4 or TestNG, `@ParameterizedTest`, a `@Suite`, an annotation of
/// one's own that carries one of those), or when it extends or implements a type that is neither
/// the JDK's nor the project's own: a library's base class (JUnit 3's `TestCase`, Spock's
/// `Specification`) can bring tests no annotation in the module shows. When in doubt, it may: a run
/// that stops is better than a score that blames tests it never ran.
final class DeclaredTests {
  /// The packages of the test frameworks the JUnit Platform runs, and their extensions.
  private static final List<String> FRAMEWORKS =
      List.of(
          "org/junit/",
          "junit/",
          "org/testng/",
          "kotlin/test/",
          "net/jqwik/",
          "org/junitpioneer/",
          "io/kotest/",
          "io/cucumber/",
          "org/spockframework/",
          "spock/");

  /// Supertypes no test comes from.
  private static final List<String> PLAIN = List.of("java/", "javax/", "kotlin/");

  private DeclaredTests() {}

  /// Whether any class under `testRoots` may hold a test: see [DeclaredTests]. `classRoots` are the
  /// module's own classes, which a helper may extend.
  static boolean anyIn(List<Path> testRoots, List<Path> classRoots) {
    var tests = models(testRoots);
    var own = new HashSet<String>(names(tests));
    own.addAll(names(models(classRoots)));
    return tests.stream().anyMatch(model -> annotated(model) || extendsAnother(model, own));
  }

  /// A test framework's annotation on the class or any of its methods, or one named as tests are
  /// (`@SpringBootTest`, `@CartesianTest`).
  private static boolean annotated(ClassModel model) {
    return Stream.concat(Stream.of(model), model.methods().stream())
        .flatMap(DeclaredTests::annotations)
        .anyMatch(DeclaredTests::testAnnotation);
  }

  private static boolean testAnnotation(String type) {
    return FRAMEWORKS.stream().anyMatch(type::startsWith)
        || type.endsWith("Test")
        || type.endsWith("Tests");
  }

  /// The annotations on `element`, each by its internal name, `org/junit/jupiter/api/Test`.
  private static Stream<String> annotations(AttributedElement element) {
    return Stream.concat(
            element.findAttribute(Attributes.runtimeVisibleAnnotations()).stream()
                .flatMap(attribute -> attribute.annotations().stream()),
            element.findAttribute(Attributes.runtimeInvisibleAnnotations()).stream()
                .flatMap(attribute -> attribute.annotations().stream()))
        .map(annotation -> annotation.className().stringValue())
        .map(descriptor -> descriptor.substring(1, descriptor.length() - 1));
  }

  /// A superclass or interface that is neither the JDK's nor one of `own`.
  private static boolean extendsAnother(ClassModel model, Set<String> own) {
    var supertypes = Stream.concat(model.superclass().stream(), model.interfaces().stream());
    return supertypes
        .map(ClassEntry::asInternalName)
        .anyMatch(type -> !own.contains(type) && PLAIN.stream().noneMatch(type::startsWith));
  }

  private static List<String> names(List<ClassModel> models) {
    return models.stream().map(model -> model.thisClass().asInternalName()).toList();
  }

  /// Every class under the roots, nested ones included.
  private static List<ClassModel> models(List<Path> roots) {
    var context = ClassFile.of();
    var models = new ArrayList<ClassModel>();
    for (Path root : roots) {
      if (Files.isDirectory(root)) {
        classFiles(root).forEach(file -> models.add(ClassFileVersion.parse(context, file)));
      }
    }
    return models;
  }

  private static List<Path> classFiles(Path root) {
    try (Stream<Path> walk = Files.walk(root)) {
      return walk.filter(file -> file.toString().endsWith(".class")).toList();
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
